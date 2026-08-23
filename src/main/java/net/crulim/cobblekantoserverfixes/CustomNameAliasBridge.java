package net.crulim.cobblekantoserverfixes;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional compatibility bridge for Fabric Custom Names 0.3.3 on Minecraft 1.21.1.
 *
 * This bridge never mutates the real GameProfile. It delegates nickname storage, chat/list
 * display updates and the visual nameplate above the player to Fabric Custom Names.
 * Reflection keeps CobbleKantoServerFixes loadable on servers where Custom Names is absent.
 */
public final class CustomNameAliasBridge {

    private static final String MOD_ID = "eclipsescustomname";
    private static final String CUSTOM_NAME_CLASS =
            "xyz.eclipseisoffline.eclipsescustomname.CustomName";
    private static final String CONFIG_CLASS =
            "xyz.eclipseisoffline.eclipsescustomname.CustomNameConfig";
    private static final String MANAGER_CLASS =
            "xyz.eclipseisoffline.eclipsescustomname.PlayerNameManager";
    private static final String NAME_TYPE_CLASS =
            "xyz.eclipseisoffline.eclipsescustomname.PlayerNameManager$NameType";

    private static volatile Api api;
    private static volatile boolean apiResolutionAttempted;
    private static volatile boolean missingModLogged;

    private CustomNameAliasBridge() {
    }

    /** Returns true when the update was accepted for execution on the server thread. */
    public static boolean apply(ServerPlayerEntity player, String alias) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        Api resolvedApi = resolveApi();
        if (resolvedApi == null) {
            return false;
        }

        String normalizedAlias = alias == null ? null : alias.trim();
        server.execute(() -> applyOnServerThread(resolvedApi, player, normalizedAlias));
        return true;
    }

    public static boolean isAvailable() {
        return resolveApi() != null;
    }

    private static void applyOnServerThread(Api resolvedApi, ServerPlayerEntity player, String alias) {
        try {
            Object config = resolvedApi.getConfig.invoke(null);
            if (config == null) {
                throw new IllegalStateException("Fabric Custom Names returned a null config instance.");
            }

            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }

            Object manager = resolvedApi.getPlayerNameManager.invoke(null, server, config);
            Text nickname = alias == null || alias.isEmpty() ? null : Text.literal(alias);
            resolvedApi.updatePlayerName.invoke(manager, player, nickname, resolvedApi.nicknameType);
            resolvedApi.updateListName.invoke(null, player);

            if (nickname == null) {
                CobbleKantoServerFixes.LOGGER.info(
                        "Cleared Fabric Custom Names nickname for {} ({}).",
                        player.getGameProfile().getName(),
                        player.getUuid()
                );
            } else {
                CobbleKantoServerFixes.LOGGER.info(
                        "Applied Fabric Custom Names nickname {} -> {} ({}), preserving UUID, skin and player data.",
                        player.getGameProfile().getName(),
                        alias,
                        player.getUuid()
                );
            }
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            CobbleKantoServerFixes.LOGGER.error(
                    "Fabric Custom Names rejected the alias update for {} ({}).",
                    player.getGameProfile().getName(),
                    player.getUuid(),
                    cause
            );
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed safely while applying a Fabric Custom Names alias for {} ({}).",
                    player.getGameProfile().getName(),
                    player.getUuid(),
                    exception
            );
        }
    }

    private static Api resolveApi() {
        Api resolved = api;
        if (resolved != null) {
            return resolved;
        }

        if (apiResolutionAttempted) {
            return null;
        }

        synchronized (CustomNameAliasBridge.class) {
            if (api != null) {
                return api;
            }
            if (apiResolutionAttempted) {
                return null;
            }
            apiResolutionAttempted = true;

            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                if (!missingModLogged) {
                    missingModLogged = true;
                    CobbleKantoServerFixes.LOGGER.error(
                            "Network aliases require Fabric Custom Names (mod id '{}') on this backend. "
                                    + "Alias packets will be rejected until the mod is installed.",
                            MOD_ID
                    );
                }
                return null;
            }

            try {
                Class<?> customNameClass = Class.forName(CUSTOM_NAME_CLASS);
                Class<?> configClass = Class.forName(CONFIG_CLASS);
                Class<?> managerClass = Class.forName(MANAGER_CLASS);
                Class<?> nameTypeClass = Class.forName(NAME_TYPE_CLASS);

                Method getConfig = customNameClass.getMethod("getConfig");
                Method getPlayerNameManager = managerClass.getMethod(
                        "getPlayerNameManager",
                        MinecraftServer.class,
                        configClass
                );
                Method updatePlayerName = managerClass.getMethod(
                        "updatePlayerName",
                        ServerPlayerEntity.class,
                        Text.class,
                        nameTypeClass
                );
                Method updateListName = customNameClass.getMethod(
                        "updateListName",
                        ServerPlayerEntity.class
                );

                @SuppressWarnings({"rawtypes", "unchecked"})
                Object nicknameType = Enum.valueOf((Class<? extends Enum>) nameTypeClass.asSubclass(Enum.class), "NICKNAME");

                Api created = new Api(
                        getConfig,
                        getPlayerNameManager,
                        updatePlayerName,
                        updateListName,
                        nicknameType
                );
                api = created;
                CobbleKantoServerFixes.LOGGER.info(
                        "Fabric Custom Names alias API resolved successfully."
                );
                return created;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Fabric Custom Names is installed, but its 0.3.3 API could not be resolved. "
                                + "Network aliases are disabled fail-closed on this backend.",
                        exception
                );
                return null;
            }
        }
    }

    private record Api(
            Method getConfig,
            Method getPlayerNameManager,
            Method updatePlayerName,
            Method updateListName,
            Object nicknameType
    ) {
    }
}
