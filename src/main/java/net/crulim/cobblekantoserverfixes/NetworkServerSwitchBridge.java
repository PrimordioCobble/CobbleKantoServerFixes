package net.crulim.cobblekantoserverfixes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Exposes a backend command that NPC command fields and command blocks can use
 * to move players through Velocity.
 *
 * NPC example:     ckserver @s survival
 * Command block:   ckserver @p survival
 * Player shortcut: /ckserver survival
 *
 * Security rules:
 * - Any player may move only themselves.
 * - Moving another player requires permission level 2.
 * - Only explicitly allowed CobbleKanto backend names are accepted.
 */
public final class NetworkServerSwitchBridge {
    private static final List<String> ALLOWED_SERVERS = List.of("kanto", "survival");
    private static final int OTHER_PLAYER_PERMISSION_LEVEL = 2;

    private NetworkServerSwitchBridge() {
    }

    public static void register() {
        /*
         * Register the payload/channel and the command callback unconditionally.
         * Whether /ckserver is actually exposed is decided when Minecraft builds
         * the command dispatcher, using the final loaded config values.
         */
        PayloadTypeRegistry.playS2C().register(ServerSwitchPayload.ID, ServerSwitchPayload.CODEC);
        CommandRegistrationCallback.EVENT.register(NetworkServerSwitchBridge::registerCommands);

        CobbleKantoServerFixes.LOGGER.info(
                "Network server-switch bridge infrastructure registered. configuredEnabled={}",
                ServerFixesConfig.enabled && ServerFixesConfig.networkServerSwitchBridgeEnabled
        );
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.networkServerSwitchBridgeEnabled) {
            CobbleKantoServerFixes.LOGGER.info(
                    "Skipping /ckserver command registration. enabled={}, networkServerSwitchBridgeEnabled={}",
                    ServerFixesConfig.enabled,
                    ServerFixesConfig.networkServerSwitchBridgeEnabled
            );
            return;
        }

        dispatcher.register(CommandManager.literal("ckserver")
                // Convenient player/NPC-self form: /ckserver survival
                .then(CommandManager.argument("server", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(ALLOWED_SERVERS, builder))
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            return requestSwitch(
                                    context.getSource(),
                                    player,
                                    StringArgumentType.getString(context, "server")
                            );
                        }))
                // Selector/name form designed for NPC fields and command blocks:
                // ckserver @s survival
                // ckserver @p survival
                // ckserver PlayerName survival
                .then(CommandManager.argument("target", EntityArgumentType.player())
                        .then(CommandManager.argument("server", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(ALLOWED_SERVERS, builder))
                                .executes(context -> requestSwitch(
                                        context.getSource(),
                                        EntityArgumentType.getPlayer(context, "target"),
                                        StringArgumentType.getString(context, "server")
                                ))))
        );

        CobbleKantoServerFixes.LOGGER.info(
                "Registered /ckserver command for Velocity-backed server switching."
        );
    }

    private static int requestSwitch(
            ServerCommandSource source,
            ServerPlayerEntity target,
            String rawServer
    ) {
        if (!ServerFixesConfig.enabled) {
            source.sendError(Text.literal("CobbleKanto Server Fixes is disabled."));
            return 0;
        }

        if (!ServerFixesConfig.networkServerSwitchBridgeEnabled) {
            source.sendError(Text.literal("CobbleKanto network server switching is disabled on this server."));
            return 0;
        }

        String targetServer = rawServer == null
                ? ""
                : rawServer.trim().toLowerCase(Locale.ROOT);

        if (!ALLOWED_SERVERS.contains(targetServer)) {
            source.sendError(Text.literal(
                    "Unknown CobbleKanto server '" + targetServer
                            + "'. Allowed: " + String.join(", ", ALLOWED_SERVERS)
            ));
            return 0;
        }

        ServerPlayerEntity executor = source.getPlayer();
        boolean movingSelf = executor != null && executor.getUuid().equals(target.getUuid());

        if (!movingSelf && !source.hasPermissionLevel(OTHER_PLAYER_PERMISSION_LEVEL)) {
            source.sendError(Text.literal("You do not have permission to move another player between servers."));
            return 0;
        }

        try {
            ServerPlayNetworking.send(target, new ServerSwitchPayload(targetServer));
        } catch (RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to request Velocity server switch for {} -> {}.",
                    target.getGameProfile().getName(),
                    targetServer,
                    exception
            );
            source.sendError(Text.literal("Could not contact the CobbleKanto proxy."));
            return 0;
        }

        return 1;
    }
}
