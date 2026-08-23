package net.crulim.cobblekantoserverfixes;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional LuckPerms integration implemented with reflection so this mod stays
 * server-side and does not acquire a hard runtime dependency on LuckPerms.
 *
 * Security contract: every uncertain/error state is denied. No method in this
 * class ever converts a missing user, failed lookup or exception into a grant.
 */
public final class LuckPermsSecurityBridge {
    private static final String LUCKPERMS_MOD_ID = "luckperms";
    private static final long DENIAL_LOG_INTERVAL_MILLIS = 30_000L;
    private static final Object INIT_LOCK = new Object();
    private static final ConcurrentHashMap<UUID, Long> LAST_DENIAL_LOG = new ConcurrentHashMap<>();

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile Object luckPermsApi;
    private static volatile Object userManager;
    private static volatile Method getUserMethod;
    private static volatile Method loadUserMethod;
    private static volatile Method userGetCachedDataMethod;
    private static volatile Method cachedDataGetPermissionDataMethod;
    private static volatile Method cachedDataGetMetaDataMethod;
    private static volatile Method permissionDataCheckPermissionMethod;
    private static volatile Method tristateAsBooleanMethod;
    private static volatile Method metaDataGetMetaValueMethod;

    private LuckPermsSecurityBridge() {
    }

    public static boolean isLuckPermsInstalled() {
        return FabricLoader.getInstance().isModLoaded(LUCKPERMS_MOD_ID);
    }

    public static boolean isGuardActive() {
        return ServerFixesConfig.enabled
                && ServerFixesConfig.luckPermsSecurityGuardEnabled
                && isLuckPermsInstalled();
    }

    /**
     * Returns true only when LuckPerms currently has this exact UUID loaded.
     * Any initialization/reflection failure is treated as not loaded.
     */
    public static boolean isUserLoaded(UUID uniqueId) {
        if (uniqueId == null || !isGuardActive() || !ensureInitialized()) {
            return false;
        }

        try {
            return getUserMethod.invoke(userManager, uniqueId) != null;
        } catch (Throwable throwable) {
            logBridgeFailure("checking whether a LuckPerms user is loaded", throwable);
            return false;
        }
    }

    /**
     * Loads the user asynchronously through the public LuckPerms API.
     * The returned future completes with false for every error or null user.
     */
    public static CompletableFuture<Boolean> loadUserFailClosed(UUID uniqueId) {
        if (uniqueId == null || !isGuardActive() || !ensureInitialized()) {
            return CompletableFuture.completedFuture(false);
        }

        try {
            Object result = loadUserMethod.invoke(userManager, uniqueId);
            if (!(result instanceof CompletableFuture<?> originalFuture)) {
                CobbleKantoServerFixes.LOGGER.error(
                        "LuckPerms UserManager#loadUser did not return a CompletableFuture. Denying login safely."
                );
                return CompletableFuture.completedFuture(false);
            }

            return originalFuture.handle((loadedUser, throwable) -> {
                if (throwable != null) {
                    logBridgeFailure("loading a LuckPerms user", throwable);
                    return false;
                }
                return loadedUser != null;
            });
        } catch (Throwable throwable) {
            logBridgeFailure("starting a LuckPerms user load", throwable);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Full replacement for NeoAPI's LuckPerms permission lookup.
     *
     * A grant is possible only when:
     *  1) the LuckPerms user is loaded, and
     *  2) LuckPerms explicitly grants the node OR the player is already a
     *     vanilla permission-level-4 operator.
     *
     * Missing data, nulls, reflection failures and exceptions all return false.
     */
    public static boolean hasPermissionFailClosed(ServerPlayerEntity player, String permission) {
        if (player == null || permission == null || permission.isBlank()) {
            return false;
        }
        if (!isGuardActive() || !ensureInitialized()) {
            logDeniedPermission(player, permission, "LuckPerms security bridge unavailable");
            return false;
        }

        try {
            Object user = getUserMethod.invoke(userManager, player.getUuid());
            if (user == null) {
                logDeniedPermission(player, permission, "LuckPerms user not loaded");
                return false;
            }

            Object cachedData = userGetCachedDataMethod.invoke(user);
            Object permissionData = cachedDataGetPermissionDataMethod.invoke(cachedData);
            Object tristate = permissionDataCheckPermissionMethod.invoke(permissionData, permission);
            Object permissionResult = tristateAsBooleanMethod.invoke(tristate);
            boolean explicitlyGranted = Boolean.TRUE.equals(permissionResult);

            // Preserve NeoAPI's existing OP fallback, but only after the LP user
            // was positively resolved. A normal player can never reach true here
            // unless LuckPerms itself grants the node.
            return explicitlyGranted || player.hasPermissionLevel(4);
        } catch (Throwable throwable) {
            logDeniedPermission(player, permission, "permission lookup failed");
            logBridgeFailure("checking a NeoAPI permission", throwable);
            return false;
        }
    }

    /**
     * Fail-closed metadata lookup for NeoAPI. Missing/error states yield null.
     */
    public static String getMetaValueFailClosed(ServerPlayerEntity player, String metaKey) {
        if (player == null || metaKey == null || metaKey.isBlank()) {
            return null;
        }
        if (!isGuardActive() || !ensureInitialized()) {
            return null;
        }

        try {
            Object user = getUserMethod.invoke(userManager, player.getUuid());
            if (user == null) {
                return null;
            }

            Object cachedData = userGetCachedDataMethod.invoke(user);
            Object metaData = cachedDataGetMetaDataMethod.invoke(cachedData);
            Object result = metaDataGetMetaValueMethod.invoke(metaData, metaKey);
            return result instanceof String stringResult ? stringResult : null;
        } catch (Throwable throwable) {
            logBridgeFailure("reading NeoAPI LuckPerms metadata", throwable);
            return null;
        }
    }

    public static void logLoginHeld(UUID uniqueId, String playerName) {
        if (!ServerFixesConfig.logLuckPermsSecurityGuard) {
            return;
        }
        CobbleKantoServerFixes.LOGGER.warn(
                "Holding login for {} ({}) until LuckPerms user data is loaded. No permissions are granted while waiting.",
                playerName,
                uniqueId
        );
    }

    public static void logLoginReleased(UUID uniqueId, String playerName) {
        if (!ServerFixesConfig.logLuckPermsSecurityGuard) {
            return;
        }
        CobbleKantoServerFixes.LOGGER.info(
                "LuckPerms user data loaded for {} ({}); safely resuming login.",
                playerName,
                uniqueId
        );
    }

    public static void logLoginDenied(UUID uniqueId, String playerName, Throwable throwable) {
        if (throwable == null) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Denied login for {} ({}) because LuckPerms user data could not be loaded safely.",
                    playerName,
                    uniqueId
            );
        } else {
            CobbleKantoServerFixes.LOGGER.error(
                    "Denied login for {} ({}) because LuckPerms user data could not be loaded safely.",
                    playerName,
                    uniqueId,
                    unwrap(throwable)
            );
        }
    }

    private static boolean ensureInitialized() {
        if (initialized) {
            return available;
        }

        synchronized (INIT_LOCK) {
            if (initialized) {
                return available;
            }

            try {
                ClassLoader classLoader = LuckPermsSecurityBridge.class.getClassLoader();
                Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider", true, classLoader);
                Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms", true, classLoader);
                Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager", true, classLoader);
                Class<?> userClass = Class.forName("net.luckperms.api.model.user.User", true, classLoader);
                Class<?> cachedDataManagerClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, classLoader);
                Class<?> cachedPermissionDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", true, classLoader);
                Class<?> cachedMetaDataClass = Class.forName("net.luckperms.api.cacheddata.CachedMetaData", true, classLoader);
                Class<?> tristateClass = Class.forName("net.luckperms.api.util.Tristate", true, classLoader);

                Method providerGetMethod = providerClass.getMethod("get");
                Object api = providerGetMethod.invoke(null);
                if (api == null) {
                    throw new IllegalStateException("LuckPermsProvider#get returned null");
                }

                Method getUserManagerApiMethod = luckPermsClass.getMethod("getUserManager");
                Object resolvedUserManager = getUserManagerApiMethod.invoke(api);
                if (resolvedUserManager == null) {
                    throw new IllegalStateException("LuckPerms#getUserManager returned null");
                }

                luckPermsApi = api;
                userManager = resolvedUserManager;
                getUserMethod = userManagerClass.getMethod("getUser", UUID.class);
                loadUserMethod = userManagerClass.getMethod("loadUser", UUID.class);
                userGetCachedDataMethod = userClass.getMethod("getCachedData");
                cachedDataGetPermissionDataMethod = cachedDataManagerClass.getMethod("getPermissionData");
                cachedDataGetMetaDataMethod = cachedDataManagerClass.getMethod("getMetaData");
                permissionDataCheckPermissionMethod = cachedPermissionDataClass.getMethod("checkPermission", String.class);
                tristateAsBooleanMethod = tristateClass.getMethod("asBoolean");
                metaDataGetMetaValueMethod = cachedMetaDataClass.getMethod("getMetaValue", String.class);
                available = true;
            } catch (Throwable throwable) {
                available = false;
                logBridgeFailure("initializing the LuckPerms security bridge", throwable);
            } finally {
                initialized = true;
            }

            return available;
        }
    }

    private static void logDeniedPermission(ServerPlayerEntity player, String permission, String reason) {
        if (!ServerFixesConfig.logLuckPermsSecurityGuard || player == null) {
            return;
        }

        UUID uniqueId = player.getUuid();
        long now = System.currentTimeMillis();
        Long previous = LAST_DENIAL_LOG.put(uniqueId, now);
        if (previous != null && now - previous < DENIAL_LOG_INTERVAL_MILLIS) {
            return;
        }

        CobbleKantoServerFixes.LOGGER.warn(
                "Fail-closed permission denial for {} ({}): node='{}', reason={}. This path can only deny; it never grants.",
                player.getGameProfile().getName(),
                uniqueId,
                permission,
                reason
        );
    }

    private static void logBridgeFailure(String action, Throwable throwable) {
        CobbleKantoServerFixes.LOGGER.error(
                "Failed while {}. LuckPerms security guard is failing closed (deny/no metadata).",
                action,
                unwrap(throwable)
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof InvocationTargetException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
