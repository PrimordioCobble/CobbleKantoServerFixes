package net.crulim.cobblekantoserverfixes;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every player accepted into a Raid Dens instance and grants the
 * CobbleKanto Raid Orb to participants excluded by Raid Dens' minimum-damage
 * reward filter.
 *
 * <p>The bridge intentionally leaves Raid Dens' normal reward flow untouched.
 * Players who already qualify for the normal {@code RAID_END} event are skipped,
 * because CobbleKanto Core will award their orb through its existing bridge.
 * Only missing participants are completed here.</p>
 */
public final class RaidParticipantRewardBridge {
    private static final String RAID_ORB_DATA_CLASS = "net.crulim.cobblekanto.raids.RaidOrbData";
    private static final String RAID_DENS_BRIDGE_CLASS = "net.crulim.cobblekanto.raids.RaidDensBridge";

    private static final Object PARTICIPANT_LOCK = new Object();
    private static final Map<UUID, LinkedHashMap<UUID, ServerPlayerEntity>> PARTICIPANTS = new HashMap<>();
    private static final Map<Class<?>, Method> REWARD_POKEMON_METHODS = new ConcurrentHashMap<>();

    private static volatile RaidBindings raidBindings;
    private static volatile boolean raidBindingFailed;
    private static volatile CoreBindings coreBindings;
    private static volatile boolean coreBindingFailed;

    private RaidParticipantRewardBridge() {
    }

    /** Records authoritative raid membership after RaidInstance#addPlayer succeeds. */
    public static void trackParticipant(Object raidInstance, ServerPlayerEntity player) {
        if (!isEnabled() || raidInstance == null || player == null) {
            return;
        }

        try {
            UUID raidId = getRaidId(raidInstance);
            synchronized (PARTICIPANT_LOCK) {
                // put() deliberately refreshes the player object after a leave/rejoin,
                // while the UUID key guarantees one logical participant per raid.
                PARTICIPANTS
                        .computeIfAbsent(raidId, ignored -> new LinkedHashMap<>())
                        .put(player.getUuid(), player);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Could not track {} as a Cobblemon Raid Dens participant.",
                    player.getName().getString(),
                    unwrap(exception)
            );
        }
    }

    /**
     * Runs before Raid Dens distributes rewards. It removes duplicate player
     * entries created by leave/rejoin cycles and ensures active players are also
     * present in the UUID tracker.
     */
    public static void prepareSuccessfulRaid(Object raidInstance) {
        if (raidInstance == null) {
            return;
        }

        if (!isEnabled()) {
            clearRaid(raidInstance);
            return;
        }

        try {
            UUID raidId = getRaidId(raidInstance);
            List<ServerPlayerEntity> activePlayers = getActivePlayers(raidInstance);
            LinkedHashMap<UUID, ServerPlayerEntity> uniquePlayers = new LinkedHashMap<>();

            for (ServerPlayerEntity player : new ArrayList<>(activePlayers)) {
                if (player != null) {
                    // LinkedHashMap retains the original position but refreshes the
                    // value to the newest ServerPlayerEntity after a rejoin.
                    uniquePlayers.put(player.getUuid(), player);
                }
            }

            int duplicates = activePlayers.size() - uniquePlayers.size();
            if (duplicates > 0) {
                activePlayers.clear();
                activePlayers.addAll(uniquePlayers.values());
                CobbleKantoServerFixes.LOGGER.info(
                        "Removed {} duplicate Raid Dens participant entr{} before rewards for raid {}.",
                        duplicates,
                        duplicates == 1 ? "y" : "ies",
                        raidId
                );
            }

            synchronized (PARTICIPANT_LOCK) {
                LinkedHashMap<UUID, ServerPlayerEntity> tracked =
                        PARTICIPANTS.computeIfAbsent(raidId, ignored -> new LinkedHashMap<>());
                uniquePlayers.forEach(tracked::put);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Could not prepare Cobblemon Raid Dens participant rewards.",
                    unwrap(exception)
            );
        }
    }

    /** Completes missing Raid Orb rewards after Raid Dens' normal success flow. */
    public static void completeSuccessfulRaid(Object raidInstance) {
        if (raidInstance == null) {
            return;
        }

        final UUID raidId;
        final LinkedHashMap<UUID, ServerPlayerEntity> trackedPlayers;
        try {
            raidId = getRaidId(raidInstance);
            trackedPlayers = consumeParticipants(raidId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Could not resolve the completed Cobblemon Raid Dens instance.",
                    unwrap(exception)
            );
            return;
        }

        if (!isEnabled() || trackedPlayers.isEmpty()) {
            return;
        }

        try {
            List<ServerPlayerEntity> activePlayers = getActivePlayers(raidInstance);
            Map<UUID, Float> damageTracker = getDamageTracker(raidInstance);
            Object raidBoss = getRaidBoss(raidInstance);
            float requiredDamage = getRequiredDamage(raidBoss);

            Set<UUID> normallyRewarded = new LinkedHashSet<>();
            for (ServerPlayerEntity player : activePlayers) {
                if (player == null) {
                    continue;
                }

                float damage = damageTracker.getOrDefault(player.getUuid(), 0.0F);
                if (requiredDamage <= 0.0F || damage >= requiredDamage) {
                    normallyRewarded.add(player.getUuid());
                }
            }

            int granted = 0;
            int offline = 0;
            int failed = 0;
            for (Map.Entry<UUID, ServerPlayerEntity> entry : trackedPlayers.entrySet()) {
                UUID playerId = entry.getKey();
                if (normallyRewarded.contains(playerId)) {
                    continue;
                }

                ServerPlayerEntity player = resolveOnlinePlayer(playerId, entry.getValue());
                if (player == null) {
                    offline++;
                    CobbleKantoServerFixes.LOGGER.warn(
                            "Could not grant the guaranteed Raid Orb for raid {} to offline player {}.",
                            raidId,
                            playerId
                    );
                    continue;
                }

                if (giveRaidOrb(player, raidBoss)) {
                    granted++;
                    CobbleKantoServerFixes.LOGGER.info(
                            "Granted guaranteed Raid Orb to {} for raid {} (normal minimum-damage reward was not received).",
                            player.getName().getString(),
                            raidId
                    );
                } else {
                    failed++;
                }
            }

            CobbleKantoServerFixes.LOGGER.info(
                    "Raid {} participant reward pass complete: tracked={}, normal={}, guaranteed={}, offline={}, failed={}.",
                    raidId,
                    trackedPlayers.size(),
                    normallyRewarded.size(),
                    granted,
                    offline,
                    failed
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to complete guaranteed CobbleKanto Raid Orb rewards for raid {}.",
                    raidId,
                    unwrap(exception)
            );
        }
    }

    /** Clears tracking for failed, cancelled, cleaned, or otherwise closed raids. */
    public static void clearRaid(Object raidInstance) {
        if (raidInstance == null) {
            return;
        }

        try {
            UUID raidId = getRaidId(raidInstance);
            synchronized (PARTICIPANT_LOCK) {
                PARTICIPANTS.remove(raidId);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.debug(
                    "Could not clear a closed Cobblemon Raid Dens participant tracker.",
                    unwrap(exception)
            );
        }
    }

    private static boolean isEnabled() {
        return ServerFixesConfig.enabled && ServerFixesConfig.grantRaidOrbToAllParticipants;
    }

    private static LinkedHashMap<UUID, ServerPlayerEntity> consumeParticipants(UUID raidId) {
        synchronized (PARTICIPANT_LOCK) {
            LinkedHashMap<UUID, ServerPlayerEntity> participants = PARTICIPANTS.remove(raidId);
            return participants == null ? new LinkedHashMap<>() : new LinkedHashMap<>(participants);
        }
    }

    private static ServerPlayerEntity resolveOnlinePlayer(UUID playerId, ServerPlayerEntity lastKnownPlayer) {
        if (lastKnownPlayer == null) {
            return null;
        }

        MinecraftServer server = lastKnownPlayer.getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerManager().getPlayer(playerId);
    }

    private static boolean giveRaidOrb(ServerPlayerEntity player, Object raidBoss)
            throws ReflectiveOperationException {
        if (raidBoss == null) {
            return false;
        }

        if (RaidOrbShinyRateBridge.giveRaidOrbFromRaidBoss(player, raidBoss)) {
            return true;
        }

        Object rewardPokemon = createRewardPokemon(raidBoss, player);
        if (rewardPokemon == null) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Raid Dens did not create a reward Pokémon for {}. No guaranteed Raid Orb was generated.",
                    player.getName().getString()
            );
            return false;
        }

        CoreBindings bindings = getCoreBindings();
        if (bindings == null) {
            return false;
        }

        Object optionalValue = bindings.fromPokemon.invoke(null, rewardPokemon, "raiddens");
        if (!(optionalValue instanceof Optional<?> optional) || optional.isEmpty()) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "CobbleKanto could not convert the Raid Dens reward Pokémon for {} into a Raid Orb.",
                    player.getName().getString()
            );
            return false;
        }

        bindings.giveRaidOrb.invoke(null, player, optional.get());
        return true;
    }

    private static Object createRewardPokemon(Object raidBoss, ServerPlayerEntity player)
            throws ReflectiveOperationException {
        Method method = REWARD_POKEMON_METHODS.get(raidBoss.getClass());
        if (method == null) {
            method = findRewardPokemonMethod(raidBoss.getClass(), player.getClass());
            REWARD_POKEMON_METHODS.put(raidBoss.getClass(), method);
        }
        return method.invoke(raidBoss, player);
    }

    private static Method findRewardPokemonMethod(Class<?> raidBossClass, Class<?> playerClass)
            throws NoSuchMethodException {
        for (Method method : raidBossClass.getMethods()) {
            if (!method.getName().equals("getRewardPokemon") || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isAssignableFrom(playerClass)) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException(raidBossClass.getName() + "#getRewardPokemon(ServerPlayer)");
    }

    private static float getRequiredDamage(Object raidBoss) throws ReflectiveOperationException {
        Method method;
        try {
            method = raidBoss.getClass().getMethod("getRequiredDamage");
        } catch (NoSuchMethodException ignored) {
            method = raidBoss.getClass().getDeclaredMethod("getRequiredDamage");
        }
        method.setAccessible(true);
        Object result = method.invoke(raidBoss);
        if (result instanceof Number number) {
            return number.floatValue();
        }
        throw new IllegalStateException("RaidBoss#getRequiredDamage did not return a number");
    }

    private static UUID getRaidId(Object raidInstance) throws ReflectiveOperationException {
        Object value = getRaidBindings(raidInstance.getClass()).raidId.get(raidInstance);
        if (value instanceof UUID raidId) {
            return raidId;
        }
        throw new IllegalStateException("RaidInstance#raid was not a UUID");
    }

    @SuppressWarnings("unchecked")
    private static List<ServerPlayerEntity> getActivePlayers(Object raidInstance)
            throws ReflectiveOperationException {
        Object value = getRaidBindings(raidInstance.getClass()).activePlayers.get(raidInstance);
        if (value instanceof List<?> list) {
            return (List<ServerPlayerEntity>) list;
        }
        throw new IllegalStateException("RaidInstance#activePlayers was not a List");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Float> getDamageTracker(Object raidInstance)
            throws ReflectiveOperationException {
        Object value = getRaidBindings(raidInstance.getClass()).damageTracker.get(raidInstance);
        if (value instanceof Map<?, ?> map) {
            return (Map<UUID, Float>) map;
        }
        throw new IllegalStateException("RaidInstance#damageTracker was not a Map");
    }

    private static Object getRaidBoss(Object raidInstance) throws ReflectiveOperationException {
        return getRaidBindings(raidInstance.getClass()).getRaidBoss.invoke(raidInstance);
    }

    private static RaidBindings getRaidBindings(Class<?> raidInstanceClass)
            throws ReflectiveOperationException {
        RaidBindings current = raidBindings;
        if (current != null && current.raidInstanceClass == raidInstanceClass) {
            return current;
        }
        if (raidBindingFailed) {
            throw new IllegalStateException("Raid Dens compatibility binding previously failed");
        }

        synchronized (RaidParticipantRewardBridge.class) {
            current = raidBindings;
            if (current != null && current.raidInstanceClass == raidInstanceClass) {
                return current;
            }
            if (raidBindingFailed) {
                throw new IllegalStateException("Raid Dens compatibility binding previously failed");
            }

            try {
                Field raidId = requireField(raidInstanceClass, "raid", UUID.class);
                Field activePlayers = requireField(raidInstanceClass, "activePlayers", List.class);
                Field damageTracker = requireField(raidInstanceClass, "damageTracker", Map.class);
                Method getRaidBoss = requireMethod(raidInstanceClass, "getRaidBoss", false);

                current = new RaidBindings(
                        raidInstanceClass,
                        raidId,
                        activePlayers,
                        damageTracker,
                        getRaidBoss
                );
                raidBindings = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                raidBindingFailed = true;
                CobbleKantoServerFixes.LOGGER.error(
                        "Cobblemon Raid Dens participant reward compatibility binding failed. " +
                                "Expected Raid Dens 0.11.1 for Minecraft 1.21.1.",
                        unwrap(exception)
                );
                throw exception;
            }
        }
    }

    private static CoreBindings getCoreBindings() throws ReflectiveOperationException {
        CoreBindings current = coreBindings;
        if (current != null) {
            return current;
        }
        if (coreBindingFailed) {
            return null;
        }

        synchronized (RaidParticipantRewardBridge.class) {
            current = coreBindings;
            if (current != null) {
                return current;
            }
            if (coreBindingFailed) {
                return null;
            }

            try {
                ClassLoader loader = RaidParticipantRewardBridge.class.getClassLoader();
                Class<?> raidOrbDataClass = Class.forName(RAID_ORB_DATA_CLASS, false, loader);
                Class<?> raidDensBridgeClass = Class.forName(RAID_DENS_BRIDGE_CLASS, false, loader);

                Method fromPokemon = requireMethod(
                        raidOrbDataClass,
                        "fromPokemon",
                        true,
                        Object.class,
                        String.class
                );
                Method giveRaidOrb = requireMethod(
                        raidDensBridgeClass,
                        "giveRaidOrb",
                        true,
                        ServerPlayerEntity.class,
                        raidOrbDataClass
                );

                current = new CoreBindings(fromPokemon, giveRaidOrb);
                coreBindings = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                coreBindingFailed = true;
                CobbleKantoServerFixes.LOGGER.error(
                        "CobbleKanto Core Raid Orb compatibility binding failed. " +
                                "Guaranteed Raid Orb rewards cannot be created without the matching CobbleKanto Core.",
                        unwrap(exception)
                );
                throw exception;
            }
        }
    }

    private static Field requireField(Class<?> owner, String name, Class<?> expectedType)
            throws NoSuchFieldException {
        Field field;
        try {
            field = owner.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            field = owner.getField(name);
        }
        if (!expectedType.isAssignableFrom(field.getType())) {
            throw new NoSuchFieldException(
                    owner.getName() + "#" + name + " has unexpected type " + field.getType().getName()
            );
        }
        field.setAccessible(true);
        return field;
    }

    private static Method requireMethod(
            Class<?> owner,
            String name,
            boolean mustBeStatic,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method method;
        try {
            method = owner.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            method = owner.getMethod(name, parameterTypes);
        }
        if (Modifier.isStatic(method.getModifiers()) != mustBeStatic) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " has unexpected modifiers");
        }
        method.setAccessible(true);
        return method;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return throwable;
    }

    private record RaidBindings(
            Class<?> raidInstanceClass,
            Field raidId,
            Field activePlayers,
            Field damageTracker,
            Method getRaidBoss
    ) {
    }

    private record CoreBindings(Method fromPokemon, Method giveRaidOrb) {
    }
}
