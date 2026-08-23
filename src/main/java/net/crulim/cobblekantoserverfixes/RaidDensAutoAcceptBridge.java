package net.crulim.cobblekantoserverfixes;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Server-only bridge into Cobblemon Raid Dens' authoritative request acceptance path.
 *
 * <p>Reflection keeps Raid Dens optional at class-load time, while all raid state changes are
 * still delegated to Raid Dens' own {@code RequestResponsePacket#handleServer} implementation.</p>
 */
public final class RaidDensAutoAcceptBridge {
    private static final String RAID_HELPER_CLASS = "com.necro.raid.dens.common.raids.helpers.RaidHelper";
    private static final String RAID_JOIN_HELPER_CLASS = "com.necro.raid.dens.common.raids.helpers.RaidJoinHelper";
    private static final String RAID_INSTANCE_CLASS = "com.necro.raid.dens.common.raids.RaidInstance";
    private static final String REQUEST_HANDLER_CLASS = "com.necro.raid.dens.common.raids.RequestHandler";
    private static final String REQUEST_RESPONSE_PACKET_CLASS = "com.necro.raid.dens.common.network.packets.RequestResponsePacket";

    private static volatile Bindings bindings;
    private static volatile boolean bindingFailed;

    private RaidDensAutoAcceptBridge() {
    }

    public static Result tryAutoAccept(PlayerEntity player, Object blockEntity, @Nullable ItemStack key) {
        if (!(player instanceof ServerPlayerEntity joiningPlayer) || blockEntity == null) {
            return Result.PASS_THROUGH;
        }

        Bindings currentBindings = getBindings(blockEntity.getClass());
        if (currentBindings == null) {
            // The mixin only calls this bridge from RaidCrystalBlock. If its expected 0.11.1
            // server API cannot be bound, reject instead of falling back to a client prompt.
            return Result.REJECTED;
        }

        final ServerPlayerEntity host;
        try {
            host = resolveHost(currentBindings, joiningPlayer, blockEntity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Could not resolve the host for a Cobblemon Raid Dens auto-join request. The request was rejected safely.",
                    unwrap(exception)
            );
            return Result.REJECTED;
        }

        if (host == null) {
            // Preserve Raid Dens' own no-raid/no-host message and return value.
            return Result.PASS_THROUGH;
        }

        boolean queued = false;
        try {
            currentBindings.addToQueue.invoke(null, joiningPlayer, key);
            queued = true;

            currentBindings.addRequest.invoke(null, host, joiningPlayer);
            Object responsePacket = currentBindings.requestPacketConstructor.newInstance(
                    true,
                    joiningPlayer.getName().getString()
            );
            currentBindings.handleServer.invoke(responsePacket, host);

            boolean stillQueued = invokeBoolean(currentBindings.isInQueue, null, joiningPlayer);
            boolean participating = invokeBoolean(currentBindings.isParticipating, null, joiningPlayer, false);

            removePendingHostRequest(currentBindings, host, joiningPlayer);

            if (participating && !stillQueued) {
                CobbleKantoServerFixes.LOGGER.debug(
                        "Auto-accepted Cobblemon Raid Dens join request for {} (host {}).",
                        joiningPlayer.getName().getString(),
                        host.getName().getString()
                );
                return Result.ACCEPTED;
            }

            // The native acceptance path can exit early when the lobby becomes full, the join
            // event vetoes entry, the region vanishes, or participant registration fails.
            // Remove temporary state and return false so the caller does not consume the key.
            if (stillQueued) {
                currentBindings.removeFromQueue.invoke(null, joiningPlayer, false);
            }
            return Result.REJECTED;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (queued) {
                safelyRemoveFromJoinQueue(currentBindings, joiningPlayer);
            }
            removePendingHostRequest(currentBindings, host, joiningPlayer);
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to auto-accept a Cobblemon Raid Dens join request for {}. The request was cancelled safely.",
                    joiningPlayer.getName().getString(),
                    unwrap(exception)
            );
            return Result.REJECTED;
        }
    }

    private static @Nullable ServerPlayerEntity resolveHost(
            Bindings currentBindings,
            ServerPlayerEntity joiningPlayer,
            Object blockEntity
    ) throws ReflectiveOperationException {
        Object raidUuidValue = currentBindings.getBlockEntityUuid.invoke(blockEntity);
        if (raidUuidValue == null) {
            return null;
        }
        if (!(raidUuidValue instanceof UUID raidUuid)) {
            throw new IllegalStateException("RaidCrystalBlockEntity#getUuid returned " + raidUuidValue.getClass().getName());
        }

        Object activeRaidsValue = currentBindings.activeRaids.get(null);
        if (!(activeRaidsValue instanceof Map<?, ?> activeRaids)) {
            throw new IllegalStateException("RaidHelper#ACTIVE_RAIDS is not a Map");
        }

        Object raidInstance = activeRaids.get(raidUuid);
        if (raidInstance == null) {
            return null;
        }

        Object hostUuidValue = currentBindings.getRaidHost.invoke(raidInstance);
        if (hostUuidValue == null) {
            return null;
        }
        if (!(hostUuidValue instanceof UUID hostUuid)) {
            throw new IllegalStateException("RaidInstance#getHost returned " + hostUuidValue.getClass().getName());
        }

        MinecraftServer server = joiningPlayer.getServer();
        return server == null ? null : server.getPlayerManager().getPlayer(hostUuid);
    }

    private static @Nullable Bindings getBindings(Class<?> blockEntityClass) {
        Bindings currentBindings = bindings;
        if (currentBindings != null && currentBindings.blockEntityClass == blockEntityClass) {
            return currentBindings;
        }
        if (bindingFailed) {
            return null;
        }

        synchronized (RaidDensAutoAcceptBridge.class) {
            currentBindings = bindings;
            if (currentBindings != null && currentBindings.blockEntityClass == blockEntityClass) {
                return currentBindings;
            }
            if (bindingFailed) {
                return null;
            }

            try {
                ClassLoader classLoader = blockEntityClass.getClassLoader();
                Class<?> raidHelperClass = Class.forName(RAID_HELPER_CLASS, false, classLoader);
                Class<?> raidJoinHelperClass = Class.forName(RAID_JOIN_HELPER_CLASS, false, classLoader);
                Class<?> raidInstanceClass = Class.forName(RAID_INSTANCE_CLASS, false, classLoader);
                Class<?> requestHandlerClass = Class.forName(REQUEST_HANDLER_CLASS, false, classLoader);
                Class<?> requestPacketClass = Class.forName(REQUEST_RESPONSE_PACKET_CLASS, false, classLoader);

                currentBindings = new Bindings(
                        blockEntityClass,
                        requireMethod(blockEntityClass, "getUuid", false),
                        requireField(raidHelperClass, "ACTIVE_RAIDS", true, Map.class),
                        requireMethod(raidInstanceClass, "getHost", false),
                        requireMethod(raidJoinHelperClass, "addToQueue", true, PlayerEntity.class, ItemStack.class),
                        requireMethod(raidHelperClass, "addRequest", true, ServerPlayerEntity.class, PlayerEntity.class),
                        requireConstructor(requestPacketClass, boolean.class, String.class),
                        requireMethod(requestPacketClass, "handleServer", false, ServerPlayerEntity.class),
                        requireMethod(raidJoinHelperClass, "isInQueue", true, PlayerEntity.class),
                        requireMethod(raidJoinHelperClass, "isParticipating", true, PlayerEntity.class, boolean.class),
                        requireMethod(raidJoinHelperClass, "removeFromQueue", true, PlayerEntity.class, boolean.class),
                        requireMethod(raidHelperClass, "getRequest", true, ServerPlayerEntity.class),
                        requireMethod(requestHandlerClass, "removePlayer", false, PlayerEntity.class)
                );

                requireReturnType(currentBindings.getBlockEntityUuid, UUID.class);
                requireReturnType(currentBindings.getRaidHost, UUID.class);
                requireBooleanReturnType(currentBindings.isInQueue);
                requireBooleanReturnType(currentBindings.isParticipating);
                requireReturnType(currentBindings.getRequest, requestHandlerClass);

                bindings = currentBindings;
                return currentBindings;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                bindingFailed = true;
                CobbleKantoServerFixes.LOGGER.error(
                        "Cobblemon Raid Dens auto-accept compatibility binding failed. " +
                                "Expected Raid Dens 0.11.1 for Minecraft 1.21.1; join attempts will be rejected while auto-accept is enabled.",
                        unwrap(exception)
                );
                return null;
            }
        }
    }

    private static void safelyRemoveFromJoinQueue(Bindings currentBindings, PlayerEntity player) {
        try {
            if (invokeBoolean(currentBindings.isInQueue, null, player)) {
                currentBindings.removeFromQueue.invoke(null, player, false);
            }
        } catch (ReflectiveOperationException | RuntimeException cleanupException) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not clean a failed Cobblemon Raid Dens auto-join queue entry for {}.",
                    player.getName().getString(),
                    unwrap(cleanupException)
            );
        }
    }

    private static void removePendingHostRequest(
            Bindings currentBindings,
            ServerPlayerEntity host,
            PlayerEntity joiningPlayer
    ) {
        try {
            Object requestHandler = currentBindings.getRequest.invoke(null, host);
            if (requestHandler != null) {
                currentBindings.removeRequestPlayer.invoke(requestHandler, joiningPlayer);
            }
        } catch (ReflectiveOperationException | RuntimeException cleanupException) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not clean a Cobblemon Raid Dens host request entry for {}.",
                    joiningPlayer.getName().getString(),
                    unwrap(cleanupException)
            );
        }
    }

    private static boolean invokeBoolean(Method method, @Nullable Object target, Object... arguments)
            throws ReflectiveOperationException {
        Object result = method.invoke(target, arguments);
        return result instanceof Boolean value && value;
    }

    private static Method requireMethod(
            Class<?> owner,
            String name,
            boolean mustBeStatic,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method method;
        try {
            method = owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            method = owner.getDeclaredMethod(name, parameterTypes);
        }

        if (Modifier.isStatic(method.getModifiers()) != mustBeStatic) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " has unexpected modifiers");
        }
        if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " has an unexpected descriptor");
        }
        method.setAccessible(true);
        return method;
    }

    private static Field requireField(
            Class<?> owner,
            String name,
            boolean mustBeStatic,
            Class<?> expectedType
    ) throws NoSuchFieldException {
        Field field;
        try {
            field = owner.getField(name);
        } catch (NoSuchFieldException ignored) {
            field = owner.getDeclaredField(name);
        }
        if (Modifier.isStatic(field.getModifiers()) != mustBeStatic) {
            throw new NoSuchFieldException(owner.getName() + "#" + name + " has unexpected modifiers");
        }
        if (!expectedType.isAssignableFrom(field.getType())) {
            throw new NoSuchFieldException(owner.getName() + "#" + name + " has unexpected type " + field.getType().getName());
        }
        field.setAccessible(true);
        return field;
    }

    private static Constructor<?> requireConstructor(Class<?> owner, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static void requireReturnType(Method method, Class<?> expectedType) throws NoSuchMethodException {
        if (!expectedType.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException(
                    method.getDeclaringClass().getName() + "#" + method.getName() +
                            " has unexpected return type " + method.getReturnType().getName()
            );
        }
    }

    private static void requireBooleanReturnType(Method method) throws NoSuchMethodException {
        Class<?> returnType = method.getReturnType();
        if (returnType != boolean.class && returnType != Boolean.class) {
            throw new NoSuchMethodException(
                    method.getDeclaringClass().getName() + "#" + method.getName() +
                            " has unexpected return type " + returnType.getName()
            );
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return throwable;
    }

    public enum Result {
        PASS_THROUGH,
        ACCEPTED,
        REJECTED
    }

    private record Bindings(
            Class<?> blockEntityClass,
            Method getBlockEntityUuid,
            Field activeRaids,
            Method getRaidHost,
            Method addToQueue,
            Method addRequest,
            Constructor<?> requestPacketConstructor,
            Method handleServer,
            Method isInQueue,
            Method isParticipating,
            Method removeFromQueue,
            Method getRequest,
            Method removeRequestPlayer
    ) {
    }
}