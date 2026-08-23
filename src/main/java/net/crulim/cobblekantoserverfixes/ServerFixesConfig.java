package net.crulim.cobblekantoserverfixes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ServerFixesConfig {
    private static final Path CONFIG_PATH = Path.of("config", "cobblekanto", "server_fixes.json");

    public static boolean enabled = true;
    public static boolean preventFarmlandTrampling = true;
    public static boolean preventItemUseOnHangingSigns = true;
    public static boolean preventItemUseOnHangingSignsAdventureOnly = true;
    public static boolean suppressNightVisionWhileHoldingCamera = true;
    public static boolean allowExposureCameraStandInAdventure = true;
    public static boolean autoAcceptRaidJoinRequests = true;
    public static boolean allowRaidRejoinAfterFaint = true;
    public static boolean grantRaidOrbToAllParticipants = true;
    public static boolean preventRaidBossShiny = true;
    public static boolean raidOrbUsesRaidDensShinyRate = true;
    public static boolean rivalStarterScanBeforeRivalBattle = true;
    public static boolean logRivalStarterScans = true;
    public static boolean protectDangerousCommands = true;
    public static boolean suppressBackendJoinLeaveMessages = false;
    public static boolean networkServerSwitchBridgeEnabled = false;
    public static boolean networkAliasBridgeEnabled = false;
    public static String networkAliasBridgeSecret = "";
    public static boolean cobblemonHomeCrossServerBridgeEnabled = false;
    public static int cobblemonHomeBridgeAckDelayTicks = 8;
    public static boolean logCobblemonHomeCrossServerBridge = true;
    public static boolean disableSpawnerGlobalEntitySweep = true;
    public static boolean blockVanillaMobsInNether = true;
    public static boolean logBlockedVanillaMobsInNether = false;
    public static boolean preventTypeAwareVillagerTradeCrash = true;
    public static boolean radGymsTeleportGuardEnabled = true;
    public static boolean logRadGymsTeleportGuard = true;
    public static boolean cobblemonCatchBehaviorBridgeEnabled = true;
    public static boolean disableCobblemonCriticalCaptureRolls = true;
    public static boolean normalizeCobblemonPokedexCatchPresentation = true;
    public static boolean neutralizeExternalCatchRateModifiers = false;
    public static boolean logCobblemonCatchBehaviorChanges = false;
    public static boolean pokemonAnnouncementBridgeEnabled = true;
    public static boolean takeOverCobbleKantoShinyNotifications = true;
    public static boolean announceShinyCaptures = true;
    public static boolean announceLegendaryCaptures = true;
    public static boolean announceMythicalCaptures = true;
    public static boolean announceLegendaryDefeats = true;
    public static boolean announceMythicalDefeats = true;
    public static boolean announceShinyDefeats = true;
    public static boolean announceShinyHatches = true;
    public static boolean announceShinyFossilRevives = true;
    public static boolean announceShinyIncubatorRewards = true;
    public static boolean blockKantoNaturalSpawns = false;
    public static boolean logKantoNaturalSpawnFilter = true;
    public static boolean luckPermsSecurityGuardEnabled = true;
    public static int luckPermsLoginLoadTimeoutSeconds = 15;
    public static boolean logLuckPermsSecurityGuard = true;
    public static boolean travelersBackpackHuskSyncBridgeEnabled = false;
    public static boolean travelersBackpackHuskSyncBridgeSeedMode = false;
    public static boolean logTravelersBackpackHuskSyncBridge = false;
    public static boolean cobblemonPartyPcHuskSyncBridgeEnabled = false;
    public static boolean cobblemonPartyPcHuskSyncBridgeSeedMode = false;
    public static boolean logCobblemonPartyPcHuskSyncBridge = false;
    public static boolean kantoDaycareBridgeEnabled = true;
    public static boolean kantoDaycarePreventDittoOffspring = true;
    public static boolean huntsItemBridgeEnabled = true;
    public static String huntsItemId = "cobblemon:sachet";
    public static String huntsCommand = "hunts";
    public static int huntsItemCooldownTicks = 20;
    public static int kantoDaycareBaseX = 6000;
    public static int kantoDaycareBaseY = 220;
    public static int kantoDaycareBaseZ = 6000;
    public static int kantoDaycareGridWidthChunks = 20;
    public static int kantoDaycareCellSpacing = 192;
    public static int kantoDaycareVerticalLayers = 5;
    public static int kantoDaycareVerticalSpacing = 20;
    public static int kantoDaycarePlatformRadius = 5;
    public static int kantoDaycareRoamRadius = 12;
    public static int kantoDaycareForceLoadTicks = 200;
    public static boolean kantoDaycareCleanupManagedForcedChunksOnStart = true;
    public static int kantoDaycareCommandPermissionLevel = 4;
    public static boolean kantoDaycareTeleportToTechnicalCell = true;
    public static int kantoDaycareReturnGraceTicks = 20;
    public static List<String> allowedKantoNaturalSpawnSpecies = defaultAllowedKantoNaturalSpawnSpecies();
    public static List<String> protectedCommands = defaultProtectedCommands();
    public static List<String> adventureCameraStandItemIds = defaultAdventureCameraStandItemIds();
    public static List<String> nightVisionCameraItemIds = defaultCameraItemIds();
    public static List<String> nightVisionCameraItemPathKeywords = defaultCameraItemPathKeywords();

    private ServerFixesConfig() {
    }

    public static void init() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.notExists(CONFIG_PATH)) {
                writeDefaultConfig();
                CobbleKantoServerFixes.LOGGER.info("Created default CobbleKanto server fixes config at {}.", CONFIG_PATH);
            } else {
                migrateVillagerTradeFixConfigKeys();
                migrateCatchBehaviorConfigKeys();
                migrateCobblemonHomeBridgeConfigKeys();
                migratePokemonAnnouncementConfigKeys();
                migrateVanillaNetherMobGuardConfigKeys();
                migrateRadGymsTeleportGuardConfigKeys();
            }

            load();
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.error("Failed to load CobbleKanto server fixes config. Using safe defaults.", exception);
            enabled = true;
            preventFarmlandTrampling = true;
            preventItemUseOnHangingSigns = true;
            preventItemUseOnHangingSignsAdventureOnly = true;
            suppressNightVisionWhileHoldingCamera = true;
            allowExposureCameraStandInAdventure = true;
            autoAcceptRaidJoinRequests = true;
            allowRaidRejoinAfterFaint = true;
            grantRaidOrbToAllParticipants = true;
            preventRaidBossShiny = true;
            raidOrbUsesRaidDensShinyRate = true;
            rivalStarterScanBeforeRivalBattle = true;
            logRivalStarterScans = true;
            protectDangerousCommands = true;
            suppressBackendJoinLeaveMessages = false;
            networkServerSwitchBridgeEnabled = false;
            networkAliasBridgeEnabled = false;
            networkAliasBridgeSecret = "";
            cobblemonHomeCrossServerBridgeEnabled = false;
            cobblemonHomeBridgeAckDelayTicks = 8;
            logCobblemonHomeCrossServerBridge = true;
            disableSpawnerGlobalEntitySweep = true;
            blockVanillaMobsInNether = true;
            logBlockedVanillaMobsInNether = false;
            preventTypeAwareVillagerTradeCrash = true;
            radGymsTeleportGuardEnabled = true;
            logRadGymsTeleportGuard = true;
            cobblemonCatchBehaviorBridgeEnabled = true;
            disableCobblemonCriticalCaptureRolls = true;
            normalizeCobblemonPokedexCatchPresentation = true;
            neutralizeExternalCatchRateModifiers = false;
            logCobblemonCatchBehaviorChanges = false;
            pokemonAnnouncementBridgeEnabled = true;
            takeOverCobbleKantoShinyNotifications = true;
            announceShinyCaptures = true;
            announceLegendaryCaptures = true;
            announceMythicalCaptures = true;
            announceLegendaryDefeats = true;
            announceMythicalDefeats = true;
            announceShinyDefeats = true;
            announceShinyHatches = true;
            announceShinyFossilRevives = true;
            announceShinyIncubatorRewards = true;
            blockKantoNaturalSpawns = false;
            logKantoNaturalSpawnFilter = true;
            luckPermsSecurityGuardEnabled = true;
            luckPermsLoginLoadTimeoutSeconds = 15;
            logLuckPermsSecurityGuard = true;
            travelersBackpackHuskSyncBridgeEnabled = false;
            travelersBackpackHuskSyncBridgeSeedMode = false;
            logTravelersBackpackHuskSyncBridge = false;
            cobblemonPartyPcHuskSyncBridgeEnabled = false;
            cobblemonPartyPcHuskSyncBridgeSeedMode = false;
            logCobblemonPartyPcHuskSyncBridge = false;
            kantoDaycareBridgeEnabled = true;
            kantoDaycarePreventDittoOffspring = true;
            huntsItemBridgeEnabled = true;
            huntsItemId = "cobblemon:sachet";
            huntsCommand = "hunts";
            huntsItemCooldownTicks = 20;
            kantoDaycareBaseX = 6000;
            kantoDaycareBaseY = 220;
            kantoDaycareBaseZ = 6000;
            kantoDaycareGridWidthChunks = 20;
            kantoDaycareCellSpacing = 192;
            kantoDaycareVerticalLayers = 5;
            kantoDaycareVerticalSpacing = 20;
            kantoDaycarePlatformRadius = 5;
            kantoDaycareRoamRadius = 12;
            kantoDaycareForceLoadTicks = 200;
            kantoDaycareCleanupManagedForcedChunksOnStart = true;
            kantoDaycareCommandPermissionLevel = 4;
            kantoDaycareTeleportToTechnicalCell = true;
            kantoDaycareReturnGraceTicks = 20;
            allowedKantoNaturalSpawnSpecies = defaultAllowedKantoNaturalSpawnSpecies();
            protectedCommands = defaultProtectedCommands();
            adventureCameraStandItemIds = defaultAdventureCameraStandItemIds();
            nightVisionCameraItemIds = defaultCameraItemIds();
            nightVisionCameraItemPathKeywords = defaultCameraItemPathKeywords();
        }
    }

    private static void load() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        enabled = readBoolean(json, "enabled", enabled);
        preventFarmlandTrampling = readBoolean(json, "preventFarmlandTrampling", preventFarmlandTrampling);
        preventItemUseOnHangingSigns = readBoolean(json, "preventItemUseOnHangingSigns", preventItemUseOnHangingSigns);
        preventItemUseOnHangingSignsAdventureOnly = readBoolean(json, "preventItemUseOnHangingSignsAdventureOnly", preventItemUseOnHangingSignsAdventureOnly);
        suppressNightVisionWhileHoldingCamera = readBoolean(json, "suppressNightVisionWhileHoldingCamera", suppressNightVisionWhileHoldingCamera);
        allowExposureCameraStandInAdventure = readBoolean(json, "allowExposureCameraStandInAdventure", allowExposureCameraStandInAdventure);
        autoAcceptRaidJoinRequests = readBoolean(json, "autoAcceptRaidJoinRequests", autoAcceptRaidJoinRequests);
        allowRaidRejoinAfterFaint = readBoolean(json, "allowRaidRejoinAfterFaint", allowRaidRejoinAfterFaint);
        grantRaidOrbToAllParticipants = readBoolean(json, "grantRaidOrbToAllParticipants", grantRaidOrbToAllParticipants);
        preventRaidBossShiny = readBoolean(json, "preventRaidBossShiny", preventRaidBossShiny);
        raidOrbUsesRaidDensShinyRate = readBoolean(json, "raidOrbUsesRaidDensShinyRate", raidOrbUsesRaidDensShinyRate);
        rivalStarterScanBeforeRivalBattle = readBoolean(json, "rivalStarterScanBeforeRivalBattle", rivalStarterScanBeforeRivalBattle);
        logRivalStarterScans = readBoolean(json, "logRivalStarterScans", logRivalStarterScans);
        protectDangerousCommands = readBoolean(json, "protectDangerousCommands", protectDangerousCommands);
        suppressBackendJoinLeaveMessages = readBoolean(json, "suppressBackendJoinLeaveMessages", suppressBackendJoinLeaveMessages);
        networkServerSwitchBridgeEnabled = readBoolean(json, "networkServerSwitchBridgeEnabled", networkServerSwitchBridgeEnabled);
        networkAliasBridgeEnabled = readBoolean(json, "networkAliasBridgeEnabled", networkAliasBridgeEnabled);
        networkAliasBridgeSecret = readString(json, "networkAliasBridgeSecret", networkAliasBridgeSecret).trim();
        cobblemonHomeCrossServerBridgeEnabled = readBoolean(json, "cobblemonHomeCrossServerBridgeEnabled", cobblemonHomeCrossServerBridgeEnabled);
        cobblemonHomeBridgeAckDelayTicks = Math.max(1, Math.min(100, readInt(json, "cobblemonHomeBridgeAckDelayTicks", cobblemonHomeBridgeAckDelayTicks)));
        logCobblemonHomeCrossServerBridge = readBoolean(json, "logCobblemonHomeCrossServerBridge", logCobblemonHomeCrossServerBridge);
        disableSpawnerGlobalEntitySweep = readBoolean(json, "disableSpawnerGlobalEntitySweep", disableSpawnerGlobalEntitySweep);
        blockVanillaMobsInNether = readBoolean(json, "blockVanillaMobsInNether", blockVanillaMobsInNether);
        logBlockedVanillaMobsInNether = readBoolean(json, "logBlockedVanillaMobsInNether", logBlockedVanillaMobsInNether);
        preventTypeAwareVillagerTradeCrash = readBoolean(json, "preventTypeAwareVillagerTradeCrash", preventTypeAwareVillagerTradeCrash);
        radGymsTeleportGuardEnabled = readBoolean(json, "radGymsTeleportGuardEnabled", radGymsTeleportGuardEnabled);
        logRadGymsTeleportGuard = readBoolean(json, "logRadGymsTeleportGuard", logRadGymsTeleportGuard);
        cobblemonCatchBehaviorBridgeEnabled = readBoolean(json, "cobblemonCatchBehaviorBridgeEnabled", cobblemonCatchBehaviorBridgeEnabled);
        disableCobblemonCriticalCaptureRolls = readBoolean(json, "disableCobblemonCriticalCaptureRolls", disableCobblemonCriticalCaptureRolls);
        normalizeCobblemonPokedexCatchPresentation = readBoolean(json, "normalizeCobblemonPokedexCatchPresentation", normalizeCobblemonPokedexCatchPresentation);
        neutralizeExternalCatchRateModifiers = readBoolean(json, "neutralizeExternalCatchRateModifiers", neutralizeExternalCatchRateModifiers);
        logCobblemonCatchBehaviorChanges = readBoolean(json, "logCobblemonCatchBehaviorChanges", logCobblemonCatchBehaviorChanges);
        pokemonAnnouncementBridgeEnabled = readBoolean(json, "pokemonAnnouncementBridgeEnabled", pokemonAnnouncementBridgeEnabled);
        takeOverCobbleKantoShinyNotifications = readBoolean(json, "takeOverCobbleKantoShinyNotifications", takeOverCobbleKantoShinyNotifications);
        announceShinyCaptures = readBoolean(json, "announceShinyCaptures", announceShinyCaptures);
        announceLegendaryCaptures = readBoolean(json, "announceLegendaryCaptures", announceLegendaryCaptures);
        announceMythicalCaptures = readBoolean(json, "announceMythicalCaptures", announceMythicalCaptures);
        announceLegendaryDefeats = readBoolean(json, "announceLegendaryDefeats", announceLegendaryDefeats);
        announceMythicalDefeats = readBoolean(json, "announceMythicalDefeats", announceMythicalDefeats);
        announceShinyDefeats = readBoolean(json, "announceShinyDefeats", announceShinyDefeats);
        announceShinyHatches = readBoolean(json, "announceShinyHatches", announceShinyHatches);
        announceShinyFossilRevives = readBoolean(json, "announceShinyFossilRevives", announceShinyFossilRevives);
        announceShinyIncubatorRewards = readBoolean(json, "announceShinyIncubatorRewards", announceShinyIncubatorRewards);
        blockKantoNaturalSpawns = readBoolean(json, "blockKantoNaturalSpawns", blockKantoNaturalSpawns);
        logKantoNaturalSpawnFilter = readBoolean(json, "logKantoNaturalSpawnFilter", logKantoNaturalSpawnFilter);
        luckPermsSecurityGuardEnabled = readBoolean(json, "luckPermsSecurityGuardEnabled", luckPermsSecurityGuardEnabled);
        luckPermsLoginLoadTimeoutSeconds = Math.max(5, Math.min(60, readInt(json, "luckPermsLoginLoadTimeoutSeconds", luckPermsLoginLoadTimeoutSeconds)));
        logLuckPermsSecurityGuard = readBoolean(json, "logLuckPermsSecurityGuard", logLuckPermsSecurityGuard);
        travelersBackpackHuskSyncBridgeEnabled = readBoolean(json, "travelersBackpackHuskSyncBridgeEnabled", travelersBackpackHuskSyncBridgeEnabled);
        travelersBackpackHuskSyncBridgeSeedMode = readBoolean(json, "travelersBackpackHuskSyncBridgeSeedMode", travelersBackpackHuskSyncBridgeSeedMode);
        logTravelersBackpackHuskSyncBridge = readBoolean(json, "logTravelersBackpackHuskSyncBridge", logTravelersBackpackHuskSyncBridge);
        cobblemonPartyPcHuskSyncBridgeEnabled = readBoolean(json, "cobblemonPartyPcHuskSyncBridgeEnabled", cobblemonPartyPcHuskSyncBridgeEnabled);
        cobblemonPartyPcHuskSyncBridgeSeedMode = readBoolean(json, "cobblemonPartyPcHuskSyncBridgeSeedMode", cobblemonPartyPcHuskSyncBridgeSeedMode);
        logCobblemonPartyPcHuskSyncBridge = readBoolean(json, "logCobblemonPartyPcHuskSyncBridge", logCobblemonPartyPcHuskSyncBridge);
        kantoDaycareBridgeEnabled = readBoolean(json, "kantoDaycareBridgeEnabled", kantoDaycareBridgeEnabled);
        kantoDaycarePreventDittoOffspring = readBoolean(json, "kantoDaycarePreventDittoOffspring", kantoDaycarePreventDittoOffspring);
        huntsItemBridgeEnabled = readBoolean(json, "huntsItemBridgeEnabled", huntsItemBridgeEnabled);
        huntsItemId = readString(json, "huntsItemId", huntsItemId).trim().toLowerCase(Locale.ROOT);
        huntsCommand = readString(json, "huntsCommand", huntsCommand).trim();
        huntsItemCooldownTicks = Math.max(0, readInt(json, "huntsItemCooldownTicks", huntsItemCooldownTicks));
        kantoDaycareBaseX = readInt(json, "kantoDaycareBaseX", kantoDaycareBaseX);
        kantoDaycareBaseY = readInt(json, "kantoDaycareBaseY", kantoDaycareBaseY);
        kantoDaycareBaseZ = readInt(json, "kantoDaycareBaseZ", kantoDaycareBaseZ);
        kantoDaycareGridWidthChunks = readInt(json, "kantoDaycareGridWidthChunks", kantoDaycareGridWidthChunks);
        kantoDaycareCellSpacing = readInt(json, "kantoDaycareCellSpacing", kantoDaycareCellSpacing);
        kantoDaycareVerticalLayers = readInt(json, "kantoDaycareVerticalLayers", kantoDaycareVerticalLayers);
        kantoDaycareVerticalSpacing = readInt(json, "kantoDaycareVerticalSpacing", kantoDaycareVerticalSpacing);
        kantoDaycarePlatformRadius = readInt(json, "kantoDaycarePlatformRadius", kantoDaycarePlatformRadius);
        kantoDaycareRoamRadius = readInt(json, "kantoDaycareRoamRadius", kantoDaycareRoamRadius);
        kantoDaycareForceLoadTicks = readInt(json, "kantoDaycareForceLoadTicks", kantoDaycareForceLoadTicks);
        kantoDaycareCleanupManagedForcedChunksOnStart = readBoolean(json, "kantoDaycareCleanupManagedForcedChunksOnStart", kantoDaycareCleanupManagedForcedChunksOnStart);
        kantoDaycareCommandPermissionLevel = readInt(json, "kantoDaycareCommandPermissionLevel", kantoDaycareCommandPermissionLevel);
        kantoDaycareTeleportToTechnicalCell = readBoolean(json, "kantoDaycareTeleportToTechnicalCell", kantoDaycareTeleportToTechnicalCell);
        kantoDaycareReturnGraceTicks = readInt(json, "kantoDaycareReturnGraceTicks", kantoDaycareReturnGraceTicks);
        allowedKantoNaturalSpawnSpecies = normalizeSpeciesList(
                readStringArray(json, "allowedKantoNaturalSpawnSpecies", allowedKantoNaturalSpawnSpecies)
        );
        protectedCommands = normalizeList(readStringArray(json, "protectedCommands", protectedCommands));
        addMissingDefaultProtectedCommands();
        adventureCameraStandItemIds = normalizeList(readStringArray(json, "adventureCameraStandItemIds", adventureCameraStandItemIds));
        addMissingDefaultAdventureCameraStandItemIds();
        nightVisionCameraItemIds = normalizeList(readStringArray(json, "nightVisionCameraItemIds", nightVisionCameraItemIds));
        nightVisionCameraItemPathKeywords = normalizeList(readStringArray(json, "nightVisionCameraItemPathKeywords", nightVisionCameraItemPathKeywords));
        CobbleKantoServerFixes.LOGGER.info(
                "Loaded CobbleKanto server fixes config. enabled={}, preventFarmlandTrampling={}, preventItemUseOnHangingSigns={}, suppressNightVisionWhileHoldingCamera={}, allowExposureCameraStandInAdventure={}, autoAcceptRaidJoinRequests={}, allowRaidRejoinAfterFaint={}, grantRaidOrbToAllParticipants={}, preventRaidBossShiny={}, raidOrbUsesRaidDensShinyRate={}, rivalStarterScanBeforeRivalBattle={}, protectDangerousCommands={}, suppressBackendJoinLeaveMessages={}, networkServerSwitchBridgeEnabled={}, networkAliasBridgeEnabled={}, networkAliasBridgeSecretConfigured={}, cobblemonHomeCrossServerBridgeEnabled={}, cobblemonHomeBridgeAckDelayTicks={}, logCobblemonHomeCrossServerBridge={}, disableSpawnerGlobalEntitySweep={}, blockVanillaMobsInNether={}, logBlockedVanillaMobsInNether={}, cobblemonCatchBehaviorBridgeEnabled={}, disableCobblemonCriticalCaptureRolls={}, normalizeCobblemonPokedexCatchPresentation={}, neutralizeExternalCatchRateModifiers={}, logCobblemonCatchBehaviorChanges={}, blockKantoNaturalSpawns={}, logKantoNaturalSpawnFilter={}, allowedKantoNaturalSpawnSpecies={}, luckPermsSecurityGuardEnabled={}, luckPermsLoginLoadTimeoutSeconds={}, logLuckPermsSecurityGuard={}, travelersBackpackHuskSyncBridgeEnabled={}, travelersBackpackHuskSyncBridgeSeedMode={}, cobblemonPartyPcHuskSyncBridgeEnabled={}, cobblemonPartyPcHuskSyncBridgeSeedMode={}, kantoDaycareBridgeEnabled={}, kantoDaycarePreventDittoOffspring={}, huntsItemBridgeEnabled={}, huntsItemId={}, huntsCommand={}, kantoDaycareBase={} {} {}, adventureCameraStandItemIds={}, protectedCommands={}",
                enabled,
                preventFarmlandTrampling,
                preventItemUseOnHangingSigns,
                suppressNightVisionWhileHoldingCamera,
                allowExposureCameraStandInAdventure,
                autoAcceptRaidJoinRequests,
                allowRaidRejoinAfterFaint,
                grantRaidOrbToAllParticipants,
                preventRaidBossShiny,
                raidOrbUsesRaidDensShinyRate,
                rivalStarterScanBeforeRivalBattle,
                protectDangerousCommands,
                suppressBackendJoinLeaveMessages,
                networkServerSwitchBridgeEnabled,
                networkAliasBridgeEnabled,
                NetworkAliasAuthenticator.hasValidSecret(networkAliasBridgeSecret),
                cobblemonHomeCrossServerBridgeEnabled,
                cobblemonHomeBridgeAckDelayTicks,
                logCobblemonHomeCrossServerBridge,
                disableSpawnerGlobalEntitySweep,
                blockVanillaMobsInNether,
                logBlockedVanillaMobsInNether,
                cobblemonCatchBehaviorBridgeEnabled,
                disableCobblemonCriticalCaptureRolls,
                normalizeCobblemonPokedexCatchPresentation,
                neutralizeExternalCatchRateModifiers,
                logCobblemonCatchBehaviorChanges,
                blockKantoNaturalSpawns,
                logKantoNaturalSpawnFilter,
                allowedKantoNaturalSpawnSpecies,
                luckPermsSecurityGuardEnabled,
                luckPermsLoginLoadTimeoutSeconds,
                logLuckPermsSecurityGuard,
                travelersBackpackHuskSyncBridgeEnabled,
                travelersBackpackHuskSyncBridgeSeedMode,
                cobblemonPartyPcHuskSyncBridgeEnabled,
                cobblemonPartyPcHuskSyncBridgeSeedMode,
                kantoDaycareBridgeEnabled,
                kantoDaycarePreventDittoOffspring,
                huntsItemBridgeEnabled,
                huntsItemId,
                huntsCommand,
                kantoDaycareBaseX,
                kantoDaycareBaseY,
                kantoDaycareBaseZ,
                adventureCameraStandItemIds,
                protectedCommands
        );
    }

    private static boolean readBoolean(String json, String key, boolean fallback) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return fallback;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return fallback;
        }

        String tail = json.substring(colonIndex + 1).trim().toLowerCase(Locale.ROOT);
        if (tail.startsWith("true")) {
            return true;
        }
        if (tail.startsWith("false")) {
            return false;
        }
        return fallback;
    }

    private static int readInt(String json, String key, int fallback) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return fallback;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return fallback;
        }

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        int valueEnd = valueStart;
        if (valueEnd < json.length() && (json.charAt(valueEnd) == '-' || json.charAt(valueEnd) == '+')) {
            valueEnd++;
        }
        while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
            valueEnd++;
        }

        if (valueEnd <= valueStart) {
            return fallback;
        }

        try {
            return Integer.parseInt(json.substring(valueStart, valueEnd));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readString(String json, String key, String fallback) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return fallback;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return fallback;
        }

        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) {
            return fallback;
        }

        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = quoteStart + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaping) {
                value.append(character);
                escaping = false;
                continue;
            }
            if (character == '\\') {
                escaping = true;
                continue;
            }
            if (character == '"') {
                String result = value.toString();
                return result.isBlank() ? fallback : result;
            }
            value.append(character);
        }

        return fallback;
    }

    private static List<String> readStringArray(String json, String key, List<String> fallback) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return fallback;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return fallback;
        }

        int arrayStart = json.indexOf('[', colonIndex + 1);
        if (arrayStart < 0) {
            return fallback;
        }

        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if (arrayEnd < 0) {
            return fallback;
        }

        String arrayBody = json.substring(arrayStart + 1, arrayEnd);
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;

        for (int index = 0; index < arrayBody.length(); index++) {
            char character = arrayBody.charAt(index);
            if (escaping) {
                current.append(character);
                escaping = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaping = true;
                continue;
            }
            if (character == '"') {
                if (inString) {
                    String value = current.toString().trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                    current.setLength(0);
                    inString = false;
                } else {
                    inString = true;
                }
                continue;
            }
            if (inString) {
                current.append(character);
            }
        }

        return values.isEmpty() ? fallback : values;
    }

    private static List<String> normalizeList(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty() && !normalized.contains(cleaned)) {
                normalized.add(cleaned);
            }
        }
        return normalized;
    }

    private static List<String> normalizeSpeciesList(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.trim().toLowerCase(Locale.ROOT);
            int namespaceSeparator = cleaned.lastIndexOf(':');
            if (namespaceSeparator >= 0 && namespaceSeparator + 1 < cleaned.length()) {
                cleaned = cleaned.substring(namespaceSeparator + 1);
            }
            if (!cleaned.isEmpty() && !normalized.contains(cleaned)) {
                normalized.add(cleaned);
            }
        }
        return normalized;
    }

    /** Adds the villager trade crash guard without overwriting an existing server-specific value. */
    private static void migrateVillagerTradeFixConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "preventTypeAwareVillagerTradeCrash", true);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate villager trade crash guard because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added villager trade crash guard to existing config at {}.",
                CONFIG_PATH
        );
    }

    /**
     * Adds the capture-behavior keys introduced in 0.5.4 to an existing config without replacing
     * any of the owner's current values. Older configs therefore keep working and gain only the
     * new fields on the next server start.
     */
    private static void migrateCatchBehaviorConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "cobblemonCatchBehaviorBridgeEnabled", true);
        addMissingBooleanEntry(json, missingEntries, "disableCobblemonCriticalCaptureRolls", true);
        addMissingBooleanEntry(json, missingEntries, "normalizeCobblemonPokedexCatchPresentation", true);
        addMissingBooleanEntry(json, missingEntries, "neutralizeExternalCatchRateModifiers", false);
        addMissingBooleanEntry(json, missingEntries, "logCobblemonCatchBehaviorChanges", false);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate capture behavior settings because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added {} Cobblemon catch behavior setting(s) to existing config at {}.",
                missingEntries.size(),
                CONFIG_PATH
        );
    }

    private static void addMissingBooleanEntry(
            String json,
            List<String> missingEntries,
            String key,
            boolean defaultValue
    ) {
        if (json.contains("\"" + key + "\"")) {
            return;
        }
        missingEntries.add("  \"" + key + "\": " + defaultValue);
    }

    /** Adds Cobblemon HOME bridge keys without overwriting existing server-specific values. */
    private static void migrateCobblemonHomeBridgeConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "cobblemonHomeCrossServerBridgeEnabled", false);
        addMissingIntEntry(json, missingEntries, "cobblemonHomeBridgeAckDelayTicks", 8);
        addMissingBooleanEntry(json, missingEntries, "logCobblemonHomeCrossServerBridge", true);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate Cobblemon HOME bridge settings because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added {} Cobblemon HOME bridge setting(s) to existing config at {}.",
                missingEntries.size(),
                CONFIG_PATH
        );
    }

    /** Adds the server-only Pokémon announcement settings without overwriting existing values. */
    private static void migratePokemonAnnouncementConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "pokemonAnnouncementBridgeEnabled", true);
        addMissingBooleanEntry(json, missingEntries, "takeOverCobbleKantoShinyNotifications", true);
        addMissingBooleanEntry(json, missingEntries, "announceShinyCaptures", true);
        addMissingBooleanEntry(json, missingEntries, "announceLegendaryCaptures", true);
        addMissingBooleanEntry(json, missingEntries, "announceMythicalCaptures", true);
        addMissingBooleanEntry(json, missingEntries, "announceLegendaryDefeats", true);
        addMissingBooleanEntry(json, missingEntries, "announceMythicalDefeats", true);
        addMissingBooleanEntry(json, missingEntries, "announceShinyDefeats", true);
        addMissingBooleanEntry(json, missingEntries, "announceShinyHatches", true);
        addMissingBooleanEntry(json, missingEntries, "announceShinyFossilRevives", true);
        addMissingBooleanEntry(json, missingEntries, "announceShinyIncubatorRewards", true);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate Pokémon announcement settings because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added {} Pokémon announcement setting(s) to existing config at {}.",
                missingEntries.size(),
                CONFIG_PATH
        );
    }

    /** Adds the Nether vanilla-mob guard settings without overwriting existing values. */
    private static void migrateVanillaNetherMobGuardConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "blockVanillaMobsInNether", true);
        addMissingBooleanEntry(json, missingEntries, "logBlockedVanillaMobsInNether", false);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate Nether vanilla-mob guard settings because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added {} Nether vanilla-mob guard setting(s) to existing config at {}.",
                missingEntries.size(),
                CONFIG_PATH
        );
    }

    /** Adds the Rad Gyms stale/unsafe teleport guard settings without overwriting existing values. */
    private static void migrateRadGymsTeleportGuardConfigKeys() throws IOException {
        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        List<String> missingEntries = new ArrayList<>();

        addMissingBooleanEntry(json, missingEntries, "radGymsTeleportGuardEnabled", true);
        addMissingBooleanEntry(json, missingEntries, "logRadGymsTeleportGuard", true);

        if (missingEntries.isEmpty()) {
            return;
        }

        int closingBrace = json.lastIndexOf('}');
        if (closingBrace < 0) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not migrate Rad Gyms teleport guard settings because {} has no closing JSON object brace.",
                    CONFIG_PATH
            );
            return;
        }

        String beforeClosingBrace = json.substring(0, closingBrace).stripTrailing();
        boolean needsComma = !beforeClosingBrace.endsWith("{") && !beforeClosingBrace.endsWith(",");

        StringBuilder migrated = new StringBuilder(beforeClosingBrace);
        if (needsComma) {
            migrated.append(',');
        }
        migrated.append('\n');
        for (int index = 0; index < missingEntries.size(); index++) {
            migrated.append(missingEntries.get(index));
            if (index + 1 < missingEntries.size()) {
                migrated.append(',');
            }
            migrated.append('\n');
        }
        migrated.append("}\n");

        Files.writeString(CONFIG_PATH, migrated.toString(), StandardCharsets.UTF_8);
        CobbleKantoServerFixes.LOGGER.info(
                "Added {} Rad Gyms teleport guard setting(s) to existing config at {}.",
                missingEntries.size(),
                CONFIG_PATH
        );
    }

    private static void addMissingIntEntry(
            String json,
            List<String> missingEntries,
            String key,
            int defaultValue
    ) {
        if (json.contains("\"" + key + "\"")) {
            return;
        }
        missingEntries.add("  \"" + key + "\": " + defaultValue);
    }

    private static void writeDefaultConfig() throws IOException {
        Files.writeString(CONFIG_PATH, defaultConfig(), StandardCharsets.UTF_8);
    }

    private static String defaultConfig() {
        return "{\n" +
                "  \"enabled\": true,\n" +
                "  \"preventFarmlandTrampling\": true,\n" +
                "  \"preventItemUseOnHangingSigns\": true,\n" +
                "  \"preventItemUseOnHangingSignsAdventureOnly\": true,\n" +
                "  \"suppressNightVisionWhileHoldingCamera\": true,\n" +
                "  \"allowExposureCameraStandInAdventure\": true,\n" +
                "  \"autoAcceptRaidJoinRequests\": true,\n" +
                "  \"allowRaidRejoinAfterFaint\": true,\n" +
                "  \"grantRaidOrbToAllParticipants\": true,\n" +
                "  \"preventRaidBossShiny\": true,\n" +
                "  \"raidOrbUsesRaidDensShinyRate\": true,\n" +
                "  \"rivalStarterScanBeforeRivalBattle\": true,\n" +
                "  \"logRivalStarterScans\": true,\n" +
                "  \"protectDangerousCommands\": true,\n" +
                "  \"suppressBackendJoinLeaveMessages\": false,\n" +
                "  \"networkServerSwitchBridgeEnabled\": false,\n" +
                "  \"networkAliasBridgeEnabled\": false,\n" +
                "  \"networkAliasBridgeSecret\": \"\",\n" +
                "  \"cobblemonHomeCrossServerBridgeEnabled\": false,\n" +
                "  \"cobblemonHomeBridgeAckDelayTicks\": 8,\n" +
                "  \"logCobblemonHomeCrossServerBridge\": true,\n" +
                "  \"disableSpawnerGlobalEntitySweep\": true,\n" +
                "  \"blockVanillaMobsInNether\": true,\n" +
                "  \"logBlockedVanillaMobsInNether\": false,\n" +
                "  \"preventTypeAwareVillagerTradeCrash\": true,\n" +
                "  \"radGymsTeleportGuardEnabled\": true,\n" +
                "  \"logRadGymsTeleportGuard\": true,\n" +
                "  \"cobblemonCatchBehaviorBridgeEnabled\": true,\n" +
                "  \"disableCobblemonCriticalCaptureRolls\": true,\n" +
                "  \"normalizeCobblemonPokedexCatchPresentation\": true,\n" +
                "  \"neutralizeExternalCatchRateModifiers\": false,\n" +
                "  \"logCobblemonCatchBehaviorChanges\": false,\n" +
                "  \"pokemonAnnouncementBridgeEnabled\": true,\n" +
                "  \"takeOverCobbleKantoShinyNotifications\": true,\n" +
                "  \"announceShinyCaptures\": true,\n" +
                "  \"announceLegendaryCaptures\": true,\n" +
                "  \"announceMythicalCaptures\": true,\n" +
                "  \"announceLegendaryDefeats\": true,\n" +
                "  \"announceMythicalDefeats\": true,\n" +
                "  \"announceShinyDefeats\": true,\n" +
                "  \"announceShinyHatches\": true,\n" +
                "  \"announceShinyFossilRevives\": true,\n" +
                "  \"announceShinyIncubatorRewards\": true,\n" +
                "  \"blockKantoNaturalSpawns\": false,\n" +
                "  \"logKantoNaturalSpawnFilter\": true,\n" +
                "  \"luckPermsSecurityGuardEnabled\": true,\n" +
                "  \"luckPermsLoginLoadTimeoutSeconds\": 15,\n" +
                "  \"logLuckPermsSecurityGuard\": true,\n" +
                "  \"travelersBackpackHuskSyncBridgeEnabled\": false,\n" +
                "  \"travelersBackpackHuskSyncBridgeSeedMode\": false,\n" +
                "  \"logTravelersBackpackHuskSyncBridge\": false,\n" +
                "  \"cobblemonPartyPcHuskSyncBridgeEnabled\": false,\n" +
                "  \"cobblemonPartyPcHuskSyncBridgeSeedMode\": false,\n" +
                "  \"logCobblemonPartyPcHuskSyncBridge\": false,\n" +
                "  \"kantoDaycareBridgeEnabled\": true,\n" +
                "  \"kantoDaycarePreventDittoOffspring\": true,\n" +
                "  \"huntsItemBridgeEnabled\": true,\n" +
                "  \"huntsItemId\": \"cobblemon:sachet\",\n" +
                "  \"huntsCommand\": \"hunts\",\n" +
                "  \"huntsItemCooldownTicks\": 20,\n" +
                "  \"kantoDaycareBaseX\": 6000,\n" +
                "  \"kantoDaycareBaseY\": 220,\n" +
                "  \"kantoDaycareBaseZ\": 6000,\n" +
                "  \"kantoDaycareGridWidthChunks\": 20,\n" +
                "  \"kantoDaycareCellSpacing\": 192,\n" +
                "  \"kantoDaycareVerticalLayers\": 5,\n" +
                "  \"kantoDaycareVerticalSpacing\": 20,\n" +
                "  \"kantoDaycarePlatformRadius\": 5,\n" +
                "  \"kantoDaycareRoamRadius\": 12,\n" +
                "  \"kantoDaycareForceLoadTicks\": 200,\n" +
                "  \"kantoDaycareCleanupManagedForcedChunksOnStart\": true,\n" +
                "  \"kantoDaycareCommandPermissionLevel\": 4,\n" +
                "  \"kantoDaycareTeleportToTechnicalCell\": true,\n" +
                "  \"kantoDaycareReturnGraceTicks\": 20,\n" +
                "  \"allowedKantoNaturalSpawnSpecies\": [\n" +
                "    \"articuno\",\n" +
                "    \"zapdos\",\n" +
                "    \"moltres\",\n" +
                "    \"mewtwo\"\n" +
                "  ],\n" +
                "  \"protectedCommands\": [\n" +
                "    \"spawnluckornot\",\n" +
                "    \"kantoteleports\",\n" +
                "    \"cobblekanto reloadreceivers\",\n" +
                "    \"cobblekanto setreceiver\"\n" +
                "  ],\n" +
                "  \"adventureCameraStandItemIds\": [\n" +
                "    \"exposure:camera_stand\"\n" +
                "  ],\n" +
                "  \"nightVisionCameraItemIds\": [\n" +
                "    \"exposure_polaroid:instant_camera\",\n" +
                "    \"minecraft:spyglass\",\n" +
                "    \"exposure:camera\",\n" +
                "    \"polaroid:camera\",\n" +
                "    \"polaroid:polaroid_camera\",\n" +
                "    \"camera:camera\",\n" +
                "    \"simplecamera:camera\"\n" +
                "  ],\n" +
                "  \"nightVisionCameraItemPathKeywords\": [\n" +
                "    \"camera\",\n" +
                "    \"polaroid\"\n" +
                "  ]\n" +
                "}\n";
    }

    private static List<String> defaultAllowedKantoNaturalSpawnSpecies() {
        return normalizeSpeciesList(List.of("articuno", "zapdos", "moltres", "mewtwo"));
    }

    private static List<String> defaultProtectedCommands() {
        return normalizeList(List.of(
                "spawnluckornot",
                "kantoteleports",
                "cobblekanto reloadreceivers",
                "cobblekanto setreceiver"
        ));
    }

    private static void addMissingDefaultProtectedCommands() {
        for (String defaultProtectedCommand : defaultProtectedCommands()) {
            if (!protectedCommands.contains(defaultProtectedCommand)) {
                protectedCommands.add(defaultProtectedCommand);
            }
        }
    }

    private static List<String> defaultAdventureCameraStandItemIds() {
        return normalizeList(List.of("exposure:camera_stand"));
    }

    private static void addMissingDefaultAdventureCameraStandItemIds() {
        for (String defaultItemId : defaultAdventureCameraStandItemIds()) {
            if (!adventureCameraStandItemIds.contains(defaultItemId)) {
                adventureCameraStandItemIds.add(defaultItemId);
            }
        }
    }

    private static List<String> defaultCameraItemIds() {
        return normalizeList(List.of(
                "exposure_polaroid:instant_camera",
                "minecraft:spyglass",
                "exposure:camera",
                "polaroid:camera",
                "polaroid:polaroid_camera",
                "camera:camera",
                "simplecamera:camera"
        ));
    }

    private static List<String> defaultCameraItemPathKeywords() {
        return normalizeList(List.of("camera", "polaroid"));
    }
}