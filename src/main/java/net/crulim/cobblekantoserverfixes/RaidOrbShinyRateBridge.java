package net.crulim.cobblekantoserverfixes;

import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replaces CobbleKanto's RaidDens orb reward generation with a server-side roll
 * based on Raid Dens' shiny_rate. This allows the visual raid boss to stay normal
 * while the reward/orb rolls shiny independently per player.
 */
public final class RaidOrbShinyRateBridge {
    private static final String RAID_ORB_DATA_CLASS = "net.crulim.cobblekanto.raids.RaidOrbData";
    private static final String RAID_DENS_BRIDGE_CLASS = "net.crulim.cobblekanto.raids.RaidDensBridge";

    private static volatile CoreBindings coreBindings;
    private static volatile boolean coreBindingFailed;

    private RaidOrbShinyRateBridge() {
    }

    /** Called from a Mixin into CobbleKanto's private RaidDensBridge handler. */
    public static boolean handleCobbleKantoRaidEndEvent(Object event) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.raidOrbUsesRaidDensShinyRate || event == null) {
            return false;
        }

        try {
            if (!Boolean.TRUE.equals(invokeNoArgs(event, "isWin", "getWin"))) {
                return true;
            }

            Object playerObject = invokeNoArgs(event, "player", "getPlayer");
            if (!(playerObject instanceof ServerPlayerEntity player)) {
                return false;
            }

            Object raidBoss = invokeNoArgs(event, "raidBoss", "getRaidBoss");
            if (raidBoss == null) {
                return false;
            }

            if (!giveRaidOrbFromRaidBoss(player, raidBoss)) {
                return false;
            }

            return true;
        } catch (RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Failed to replace CobbleKanto RaidDens orb reward with shiny_rate-aware reward.",
                    unwrap(exception)
            );
            return false;
        }
    }

    public static boolean giveRaidOrbFromRaidBoss(ServerPlayerEntity player, Object raidBoss) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.raidOrbUsesRaidDensShinyRate) {
            return false;
        }
        if (player == null || raidBoss == null) {
            return false;
        }

        try {
            Object rewardPokemon = createRewardPokemonFromRaidBoss(raidBoss, player);
            if (rewardPokemon == null) {
                return false;
            }

            CoreBindings bindings = getCoreBindings();
            if (bindings == null) {
                return false;
            }

            Object optionalValue = bindings.fromPokemon.invoke(null, rewardPokemon, "raiddens");
            if (!(optionalValue instanceof Optional<?> optional) || optional.isEmpty()) {
                return false;
            }

            Object originalData = optional.get();
            Object fixedData = rebuildRaidOrbDataWithShiny(originalData, rollShinyFromRaidBoss(raidBoss));
            bindings.giveRaidOrb.invoke(null, player, fixedData);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not create a shiny_rate-aware CobbleKanto Raid Orb for {}.",
                    player.getName().getString(),
                    unwrap(exception)
            );
            return false;
        }
    }

    private static Object createRewardPokemonFromRaidBoss(Object raidBoss, ServerPlayerEntity player) {
        for (Method method : raidBoss.getClass().getMethods()) {
            if (!method.getName().equals("getRewardPokemon") || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isAssignableFrom(player.getClass())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(raidBoss, player);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                CobbleKantoServerFixes.LOGGER.warn(
                        "Raid Dens getRewardPokemon failed while creating a Raid Orb reward.",
                        unwrap(exception)
                );
                return null;
            }
        }
        return null;
    }

    private static boolean rollShinyFromRaidBoss(Object raidBoss) throws ReflectiveOperationException {
        float shinyRate = readShinyRate(raidBoss);
        if (!Float.isFinite(shinyRate) || shinyRate <= 0.0F) {
            return false;
        }
        if (shinyRate <= 1.0F) {
            return true;
        }

        int bound = Math.max(1, Math.round(shinyRate));
        return ThreadLocalRandom.current().nextInt(bound) == 0;
    }

    private static float readShinyRate(Object raidBoss) throws ReflectiveOperationException {
        Object value = invokeNoArgsThrowing(raidBoss, "getShinyRate", "shinyRate");
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return 4096.0F;
    }

    @SuppressWarnings("unchecked")
    private static Object rebuildRaidOrbDataWithShiny(Object data, boolean shiny)
            throws ReflectiveOperationException {
        Class<?> dataClass = data.getClass();
        String species = readString(data, "species", "pikachu");
        int level = readInt(data, "level", 50);
        String displayName = readString(data, "displayName", species);
        String gender = readString(data, "gender", "");
        List<String> aspects = new ArrayList<>((List<String>) readObject(data, "aspects", Collections.emptyList()));
        aspects.removeIf(aspect -> aspect != null && aspect.equalsIgnoreCase("shiny"));
        String source = readString(data, "source", "raiddens");
        String raidId = readString(data, "raidId", "");

        Constructor<?> constructor = dataClass.getConstructor(
                String.class,
                int.class,
                String.class,
                boolean.class,
                String.class,
                List.class,
                String.class,
                String.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(species, level, displayName, shiny, gender, aspects, source, raidId);
    }

    private static Object readObject(Object target, String methodName, Object fallback) {
        Object value = invokeNoArgs(target, methodName);
        return value == null ? fallback : value;
    }

    private static String readString(Object target, String methodName, String fallback) {
        Object value = invokeNoArgs(target, methodName);
        return value instanceof String string ? string : fallback;
    }

    private static int readInt(Object target, String methodName, int fallback) {
        Object value = invokeNoArgs(target, methodName);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static CoreBindings getCoreBindings() throws ReflectiveOperationException {
        CoreBindings current = coreBindings;
        if (current != null) {
            return current;
        }
        if (coreBindingFailed) {
            return null;
        }

        synchronized (RaidOrbShinyRateBridge.class) {
            current = coreBindings;
            if (current != null) {
                return current;
            }
            if (coreBindingFailed) {
                return null;
            }

            try {
                ClassLoader loader = RaidOrbShinyRateBridge.class.getClassLoader();
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
                        "CobbleKanto Raid Orb shiny_rate compatibility binding failed.",
                        unwrap(exception)
                );
                throw exception;
            }
        }
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

    private static Object invokeNoArgs(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Object invokeNoArgsThrowing(Object target, String... methodNames)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        ReflectiveOperationException lastException = null;
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException exception) {
                lastException = exception;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return throwable;
    }

    private record CoreBindings(Method fromPokemon, Method giveRaidOrb) {
    }
}
