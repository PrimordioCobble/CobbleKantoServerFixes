package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.regex.Pattern;

/**
 * Receives a proxy-authoritative public alias for the player carrying this payload.
 *
 * Security properties:
 * - The payload contains no UUID or selector: it can affect only context.player().
 * - Every instruction is HMAC-authenticated and bound to that player's UUID.
 * - The real GameProfile, skin, inventory, permissions and persistent UUID are never changed.
 * - Fabric Custom Names owns the visual nickname and nameplate implementation.
 * - Unknown, malformed, expired or disabled instructions are rejected fail-closed.
 */
public final class NetworkAliasBridge {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private NetworkAliasBridge() {
    }

    public static void register() {
        // Register even while disabled so stale proxy packets never become unknown payloads.
        PayloadTypeRegistry.playC2S().register(NetworkAliasPayload.ID, NetworkAliasPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(NetworkAliasPayload.ID, (payload, context) ->
                handle(context.player(), payload.instruction()));

        boolean configured = ServerFixesConfig.enabled
                && ServerFixesConfig.networkAliasBridgeEnabled
                && NetworkAliasAuthenticator.hasValidSecret(ServerFixesConfig.networkAliasBridgeSecret);

        CobbleKantoServerFixes.LOGGER.info(
                "Network public-alias bridge registered. configuredEnabled={}, customNamesAvailable={}",
                configured,
                !configured || CustomNameAliasBridge.isAvailable()
        );
    }

    private static void handle(ServerPlayerEntity player, String rawInstruction) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.networkAliasBridgeEnabled) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Ignored network alias payload for {} because the bridge is disabled.",
                    player.getGameProfile().getName()
            );
            return;
        }

        if (!NetworkAliasAuthenticator.hasValidSecret(ServerFixesConfig.networkAliasBridgeSecret)) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Rejected network alias payload for {}: networkAliasBridgeSecret is missing or too short.",
                    player.getUuid()
            );
            return;
        }

        String instruction = NetworkAliasAuthenticator.verify(
                        player.getUuid(),
                        rawInstruction,
                        ServerFixesConfig.networkAliasBridgeSecret
                )
                .orElse(null);
        if (instruction == null) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Rejected unauthenticated, expired or malformed network alias payload for {}.",
                    player.getUuid()
            );
            return;
        }

        instruction = instruction.trim();
        try {
            if (instruction.equals("CLEAR")) {
                if (!CustomNameAliasBridge.apply(player, null)) {
                    CobbleKantoServerFixes.LOGGER.error(
                            "Could not clear network alias for {} because Fabric Custom Names is unavailable.",
                            player.getUuid()
                    );
                }
                return;
            }

            if (!instruction.regionMatches(true, 0, "SET:", 0, 4)) {
                CobbleKantoServerFixes.LOGGER.warn(
                        "Rejected malformed network alias instruction for {}.",
                        player.getUuid()
                );
                return;
            }

            String alias = instruction.substring(4).trim();
            if (!VALID_NAME.matcher(alias).matches()) {
                CobbleKantoServerFixes.LOGGER.warn(
                        "Rejected invalid network alias '{}' for {}.",
                        alias.replaceAll("[^A-Za-z0-9_]", "?"),
                        player.getUuid()
                );
                return;
            }

            if (!CustomNameAliasBridge.apply(player, alias)) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Could not apply network alias '{}' for {} because Fabric Custom Names is unavailable.",
                        alias,
                        player.getUuid()
                );
            }
        } catch (RuntimeException | LinkageError exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed safely while applying network alias instruction for {}.",
                    player.getUuid(),
                    exception
            );
        }
    }
}
