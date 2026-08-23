package net.crulim.cobblekantoserverfixes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side bridge for Cobblemon HOME 2.5.x when clients have
 * allowCrossWorldTransfers=false and therefore cannot press HOME's Transfer button.
 *
 * Architecture:
 * - Velocity asks the source backend to prepare HOME before a tracked server switch.
 * - This class serializes the source server's HOME snapshots using HOME's own schema.
 * - It sends HOME's existing TransferToLocalPacket to the already-installed client mod.
 * - The client-side HOME handler saves the snapshots locally even if its GUI toggle is OFF.
 * - Source snapshot files are removed only after every packet has been queued successfully.
 * - A last-transfer backup is retained server-side for recovery.
 * - After a small tick delay, an ACK is sent back to Velocity; only then does the proxy switch.
 *
 * No HuskSync, database, client update or replacement HOME mod is involved.
 */
public final class CobblemonHomeCrossServerBridge {
    private static final String HOME_MOD_ID = "cobblemon_home";
    private static final String PREPARE_PREFIX = "PREPARE|";
    private static final String ACK_PREFIX = "ACK|";
    private static final Path HOME_DATA_ROOT = Path.of("cobblemon_home", "data");
    private static final Path BACKUP_ROOT = Path.of("cobblekanto_home_bridge", "backups");

    private static final Map<UUID, PendingAck> PENDING_ACKS = new HashMap<>();
    private static long serverTick;
    private static volatile ReflectionApi reflectionApi;
    private static volatile boolean reflectionResolutionAttempted;

    private CobblemonHomeCrossServerBridge() {
    }

    public static void register() {
        // Register both directions unconditionally so stale proxy packets are consumed safely.
        PayloadTypeRegistry.playC2S().register(HomeBridgeControlPayload.ID, HomeBridgeControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HomeBridgeControlPayload.ID, HomeBridgeControlPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HomeBridgeControlPayload.ID, (payload, context) ->
                handleControl(context.player(), payload.instruction()));

        ServerTickEvents.END_SERVER_TICK.register(CobblemonHomeCrossServerBridge::tickPendingAcks);

        boolean modPresent = FabricLoader.getInstance().isModLoaded(HOME_MOD_ID);
        CobbleKantoServerFixes.LOGGER.info(
                "Cobblemon HOME cross-server bridge registered. configuredEnabled={}, cobblemonHomePresent={}, ackDelayTicks={}",
                ServerFixesConfig.enabled && ServerFixesConfig.cobblemonHomeCrossServerBridgeEnabled,
                modPresent,
                ServerFixesConfig.cobblemonHomeBridgeAckDelayTicks
        );
    }

    private static void handleControl(ServerPlayerEntity player, String rawInstruction) {
        if (rawInstruction == null || !rawInstruction.startsWith(PREPARE_PREFIX)) {
            debug("Ignored malformed HOME bridge control payload for " + player.getUuid() + ".");
            return;
        }

        String[] parts = rawInstruction.split("\\|", 3);
        if (parts.length != 3 || !parts[0].equals("PREPARE")) {
            queueAck(player, "invalid", false, 0, "malformed-control");
            return;
        }

        String transferId = parts[1].trim();
        String targetServer = parts[2].trim().toLowerCase(Locale.ROOT);
        if (!isSafeToken(transferId, 64) || !isSafeToken(targetServer, 32)) {
            queueAck(player, safeTransferId(transferId), false, 0, "invalid-control");
            return;
        }

        if (!ServerFixesConfig.enabled || !ServerFixesConfig.cobblemonHomeCrossServerBridgeEnabled) {
            queueAck(player, transferId, false, 0, "bridge-disabled");
            return;
        }

        ExportResult result = exportAllServerSnapshotsToClient(player, transferId, targetServer);
        queueAck(player, transferId, result.success(), result.count(), result.reason());
    }

    private static ExportResult exportAllServerSnapshotsToClient(
            ServerPlayerEntity player,
            String transferId,
            String targetServer
    ) {
        if (!FabricLoader.getInstance().isModLoaded(HOME_MOD_ID)) {
            return ExportResult.failure("home-mod-missing");
        }

        ReflectionApi api = resolveReflectionApi();
        if (api == null) {
            return ExportResult.failure("home-api-unavailable");
        }

        try {
            boolean serverCrossWorldEnabled = (boolean) api.isAllowCrossWorldTransfers.invoke(null);
            if (!serverCrossWorldEnabled) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Cobblemon HOME bridge refused transfer for {} -> {} because server-side allowCrossWorldTransfers is false.",
                        player.getGameProfile().getName(),
                        targetServer
                );
                return ExportResult.failure("home-server-crossworld-disabled");
            }

            Object storage = api.getStorage.invoke(null);
            if (storage == null) {
                return ExportResult.failure("home-storage-unavailable");
            }

            Object rawSnapshots = api.getPlayerSnapshots.invoke(storage, player.getUuid());
            if (!(rawSnapshots instanceof Collection<?> snapshots)) {
                return ExportResult.failure("home-snapshots-unavailable");
            }

            if (snapshots.isEmpty()) {
                debug("HOME bridge: no server-side HOME snapshots for " + player.getUuid()
                        + " before switch to " + targetServer + ".");
                return ExportResult.success(0);
            }

            Object handler = api.networkHandlerField.get(null);
            if (handler == null) {
                return ExportResult.failure("home-network-handler-unavailable");
            }

            List<PreparedSnapshot> prepared = new ArrayList<>(snapshots.size());
            for (Object snapshot : new ArrayList<>(snapshots)) {
                prepared.add(prepareSnapshot(api, player, snapshot));
            }

            // Keep a server-side recovery copy before touching the live HOME files.
            Path backupDirectory = BACKUP_ROOT.resolve(player.getUuid().toString()).resolve("last");
            replaceBackupDirectory(backupDirectory, prepared, transferId, targetServer);

            // Phase 1: enqueue every HOME packet. Do not delete source snapshots yet.
            for (PreparedSnapshot snapshot : prepared) {
                Object packet = api.transferPacketConstructor.newInstance(snapshot.json());
                api.sendToPlayer.invoke(handler, player, packet);
            }

            // Phase 2: remove the live server snapshots from disk. If any deletion fails,
            // restore all snapshots from our backup and refuse the server switch.
            try {
                for (PreparedSnapshot snapshot : prepared) {
                    Files.deleteIfExists(snapshot.sourcePath());
                }
            } catch (IOException deletionFailure) {
                restoreLiveSnapshots(prepared);
                api.clearCache.invoke(storage);
                CobbleKantoServerFixes.LOGGER.error(
                        "HOME bridge rolled back source snapshots for {} because deletion failed.",
                        player.getUuid(),
                        deletionFailure
                );
                return ExportResult.failure("source-delete-failed");
            }

            try {
                api.clearCache.invoke(storage);
            } catch (ReflectiveOperationException cacheFailure) {
                restoreLiveSnapshots(prepared);
                try {
                    api.clearCache.invoke(storage);
                } catch (ReflectiveOperationException ignored) {
                    // The live files were restored; a stale cache is safer than losing snapshots.
                }
                CobbleKantoServerFixes.LOGGER.error(
                        "HOME bridge restored source snapshots for {} because HOME cache invalidation failed.",
                        player.getUuid(),
                        cacheFailure
                );
                return ExportResult.failure("home-cache-clear-failed");
            }

            CobbleKantoServerFixes.LOGGER.info(
                    "HOME bridge exported {} server snapshot(s) for {} before switch to {}. Recovery backup: {}",
                    prepared.size(),
                    player.getGameProfile().getName(),
                    targetServer,
                    backupDirectory
            );
            return ExportResult.success(prepared.size());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | IOException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "HOME bridge failed safely for {} before switch to {}.",
                    player.getUuid(),
                    targetServer,
                    exception
            );
            return ExportResult.failure("home-export-failed");
        }
    }

    private static PreparedSnapshot prepareSnapshot(
            ReflectionApi api,
            ServerPlayerEntity player,
            Object snapshot
    ) throws ReflectiveOperationException, IOException {
        UUID pokemonUuid = (UUID) api.getPokemonUuid.invoke(snapshot);
        UUID ownerUuid = (UUID) api.getOwnerUuid.invoke(snapshot);
        if (pokemonUuid == null || ownerUuid == null) {
            throw new IllegalStateException("Cobblemon HOME snapshot is missing UUID fields");
        }
        if (!player.getUuid().equals(ownerUuid)) {
            throw new IllegalStateException("Cobblemon HOME snapshot owner does not match connected player");
        }

        Path playerDirectory = HOME_DATA_ROOT.resolve(ownerUuid.toString()).normalize();
        Path sourcePath = playerDirectory.resolve(pokemonUuid + ".json").normalize();
        if (!sourcePath.startsWith(playerDirectory)) {
            throw new IllegalStateException("Cobblemon HOME snapshot resolved outside player directory");
        }
        if (Files.notExists(sourcePath)) {
            throw new IOException("Cobblemon HOME snapshot file does not exist: " + sourcePath);
        }

        // Transport HOME's exact on-disk JSON instead of reconstructing it field-by-field.
        // This preserves every field HOME 2.5.9 (and compatible future 2.5.x builds) writes.
        String json = Files.readString(sourcePath, StandardCharsets.UTF_8);
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.has("pokemonUuid") || !pokemonUuid.toString().equals(object.get("pokemonUuid").getAsString())) {
            throw new IllegalStateException("Cobblemon HOME snapshot pokemonUuid does not match filename/cache");
        }
        if (!object.has("ownerUuid") || !ownerUuid.toString().equals(object.get("ownerUuid").getAsString())) {
            throw new IllegalStateException("Cobblemon HOME snapshot ownerUuid does not match connected player");
        }
        if (!object.has("pokemonData") || object.get("pokemonData").isJsonNull()) {
            throw new IllegalStateException("Cobblemon HOME snapshot has no pokemonData");
        }

        return new PreparedSnapshot(pokemonUuid, json, sourcePath);
    }

    private static void replaceBackupDirectory(
            Path backupDirectory,
            List<PreparedSnapshot> prepared,
            String transferId,
            String targetServer
    ) throws IOException {
        deleteRecursively(backupDirectory);
        Files.createDirectories(backupDirectory);

        String manifest = "transferId=" + transferId + "\n"
                + "targetServer=" + targetServer + "\n"
                + "count=" + prepared.size() + "\n";
        Files.writeString(backupDirectory.resolve("manifest.txt"), manifest, StandardCharsets.UTF_8);

        for (PreparedSnapshot snapshot : prepared) {
            Files.writeString(
                    backupDirectory.resolve(snapshot.pokemonUuid() + ".json"),
                    snapshot.json(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private static void restoreLiveSnapshots(List<PreparedSnapshot> prepared) {
        for (PreparedSnapshot snapshot : prepared) {
            try {
                Files.createDirectories(snapshot.sourcePath().getParent());
                Path temp = snapshot.sourcePath().resolveSibling(snapshot.sourcePath().getFileName() + ".cks-restore.tmp");
                Files.writeString(temp, snapshot.json(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, snapshot.sourcePath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveFailure) {
                    Files.move(temp, snapshot.sourcePath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException restoreFailure) {
                CobbleKantoServerFixes.LOGGER.error(
                        "HOME bridge emergency restore failed for snapshot {} at {}.",
                        snapshot.pokemonUuid(),
                        snapshot.sourcePath(),
                        restoreFailure
                );
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static ReflectionApi resolveReflectionApi() {
        ReflectionApi cached = reflectionApi;
        if (cached != null) {
            return cached;
        }
        if (reflectionResolutionAttempted) {
            return null;
        }

        synchronized (CobblemonHomeCrossServerBridge.class) {
            if (reflectionApi != null) {
                return reflectionApi;
            }
            if (reflectionResolutionAttempted) {
                return null;
            }
            reflectionResolutionAttempted = true;

            try {
                Class<?> homeClass = Class.forName("cobblemon.home.CobblemonHome");
                Class<?> storageClass = Class.forName("cobblemon.home.storage.HomeStorage");
                Class<?> snapshotClass = Class.forName("cobblemon.home.storage.PokemonSnapshot");
                Class<?> networkingClass = Class.forName("cobblemon.home.network.ModNetworking");
                Class<?> networkHandlerClass = Class.forName("cobblemon.home.network.ModNetworking$NetworkHandler");
                Class<?> transferPacketClass = Class.forName("cobblemon.home.network.server.TransferToLocalPacket");
                Class<?> homeConfigClass = Class.forName("cobblemon.home.config.ModConfig");

                ReflectionApi resolved = new ReflectionApi(
                        homeClass.getMethod("getStorage"),
                        storageClass.getMethod("getPlayerSnapshots", UUID.class),
                        storageClass.getMethod("clearCache"),
                        networkingClass.getField("HANDLER"),
                        networkHandlerClass.getMethod("sendToPlayer", ServerPlayerEntity.class, Object.class),
                        transferPacketClass.getConstructor(String.class),
                        homeConfigClass.getMethod("isAllowCrossWorldTransfers"),
                        snapshotClass.getMethod("getPokemonUuid"),
                        snapshotClass.getMethod("getOwnerUuid")
                );
                reflectionApi = resolved;
                CobbleKantoServerFixes.LOGGER.info(
                        "Cobblemon HOME reflection bridge resolved successfully for installed HOME mod."
                );
                return resolved;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Could not resolve Cobblemon HOME bridge API. The bridge will fail closed and prevent tracked switches when enabled.",
                        exception
                );
                return null;
            }
        }
    }

    private static void queueAck(
            ServerPlayerEntity player,
            String transferId,
            boolean success,
            int count,
            String reason
    ) {
        int delay = Math.max(1, ServerFixesConfig.cobblemonHomeBridgeAckDelayTicks);
        PENDING_ACKS.put(
                player.getUuid(),
                new PendingAck(
                        player.getUuid(),
                        transferId,
                        success,
                        count,
                        sanitizeReason(reason),
                        serverTick + delay
                )
        );
    }

    private static void tickPendingAcks(MinecraftServer server) {
        serverTick++;
        if (PENDING_ACKS.isEmpty()) {
            return;
        }

        List<PendingAck> due = PENDING_ACKS.values().stream()
                .filter(ack -> ack.dueTick() <= serverTick)
                .toList();

        for (PendingAck ack : due) {
            PendingAck current = PENDING_ACKS.get(ack.playerUuid());
            if (current != ack) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(ack.playerUuid());
            if (player == null) {
                PENDING_ACKS.remove(ack.playerUuid(), ack);
                continue;
            }

            String status = ack.success() ? "OK" : "FAIL";
            String instruction = ACK_PREFIX
                    + ack.transferId() + "|"
                    + status + "|"
                    + ack.count() + "|"
                    + ack.reason();

            try {
                ServerPlayNetworking.send(player, new HomeBridgeControlPayload(instruction));
                PENDING_ACKS.remove(ack.playerUuid(), ack);
                debug("HOME bridge ACK sent for " + player.getUuid() + ": " + status
                        + ", count=" + ack.count() + ".");
            } catch (RuntimeException exception) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Could not send HOME bridge ACK for {}.",
                        player.getUuid(),
                        exception
                );
                PENDING_ACKS.remove(ack.playerUuid(), ack);
            }
        }
    }

    private static boolean isSafeToken(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(Character.isLetterOrDigit(character)
                    || character == '-'
                    || character == '_'
                    || character == '.')) {
                return false;
            }
        }
        return true;
    }

    private static String safeTransferId(String value) {
        return isSafeToken(value, 64) ? value : "invalid";
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String cleaned = reason.replace('|', '-').replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }

    private static void debug(String message) {
        if (ServerFixesConfig.logCobblemonHomeCrossServerBridge) {
            CobbleKantoServerFixes.LOGGER.info("[HOME-BRIDGE] {}", message);
        }
    }

    private record PreparedSnapshot(
            UUID pokemonUuid,
            String json,
            Path sourcePath
    ) {
    }

    private record ExportResult(boolean success, int count, String reason) {
        static ExportResult success(int count) {
            return new ExportResult(true, count, "ok");
        }

        static ExportResult failure(String reason) {
            return new ExportResult(false, 0, reason);
        }
    }

    private record PendingAck(
            UUID playerUuid,
            String transferId,
            boolean success,
            int count,
            String reason,
            long dueTick
    ) {
    }

    private record ReflectionApi(
            Method getStorage,
            Method getPlayerSnapshots,
            Method clearCache,
            Field networkHandlerField,
            Method sendToPlayer,
            Constructor<?> transferPacketConstructor,
            Method isAllowCrossWorldTransfers,
            Method getPokemonUuid,
            Method getOwnerUuid
    ) {
    }
}
