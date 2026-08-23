package net.crulim.cobblekantoserverfixes;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CobbleKantoServerFixes implements ModInitializer {
    public static final String MOD_ID = "cobblekanto_server_fixes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerFixesConfig.init();
        CobblemonCatchBehaviorBridge.register();
        PokemonAnnouncementBridge.register();
        HangingSignInteractionFix.register();
        CameraNightVisionSuppressor.register();
        KantoDaycareBridge.register();
        HuntsItemBridge.register();
        VanillaNetherMobGuard.register();

        NetworkServerSwitchBridge.register();
        NetworkAliasBridge.register();
        CobblemonHomeCrossServerBridge.register();
        TravelersBackpackHuskSyncBridge.register();
        CobblemonPartyPcHuskSyncBridge.register();
        LOGGER.info(
                "CobbleKanto Server Fixes initialized. enabled={}, preventFarmlandTrampling={}, preventItemUseOnHangingSigns={}, suppressNightVisionWhileHoldingCamera={}, allowExposureCameraStandInAdventure={}, autoAcceptRaidJoinRequests={}, allowRaidRejoinAfterFaint={}, grantRaidOrbToAllParticipants={}, suppressBackendJoinLeaveMessages={}, networkServerSwitchBridgeEnabled={}, networkAliasBridgeEnabled={}, networkAliasBridgeSecretConfigured={}, customNamesAliasBridgeAvailable={}, cobblemonHomeCrossServerBridgeEnabled={}, cobblemonHomeBridgeAckDelayTicks={}, disableSpawnerGlobalEntitySweep={}, blockVanillaMobsInNether={}, logBlockedVanillaMobsInNether={}, cobblemonCatchBehaviorBridgeEnabled={}, disableCobblemonCriticalCaptureRolls={}, normalizeCobblemonPokedexCatchPresentation={}, neutralizeExternalCatchRateModifiers={}, blockKantoNaturalSpawns={}, allowedKantoNaturalSpawnSpecies={}, luckPermsSecurityGuardEnabled={}, luckPermsLoginLoadTimeoutSeconds={}, travelersBackpackHuskSyncBridgeEnabled={}, travelersBackpackHuskSyncBridgeSeedMode={}, cobblemonPartyPcHuskSyncBridgeEnabled={}, cobblemonPartyPcHuskSyncBridgeSeedMode={}, adventureCameraStandItemIds={}, kantoDaycareBridgeEnabled={}, kantoDaycarePreventDittoOffspring={}, huntsItemBridgeEnabled={}, huntsItemId={}, huntsCommand={}",
                ServerFixesConfig.enabled,
                ServerFixesConfig.preventFarmlandTrampling,
                ServerFixesConfig.preventItemUseOnHangingSigns,
                ServerFixesConfig.suppressNightVisionWhileHoldingCamera,
                ServerFixesConfig.allowExposureCameraStandInAdventure,
                ServerFixesConfig.autoAcceptRaidJoinRequests,
                ServerFixesConfig.allowRaidRejoinAfterFaint,
                ServerFixesConfig.grantRaidOrbToAllParticipants,
                ServerFixesConfig.suppressBackendJoinLeaveMessages,
                ServerFixesConfig.networkServerSwitchBridgeEnabled,
                ServerFixesConfig.networkAliasBridgeEnabled,
                NetworkAliasAuthenticator.hasValidSecret(ServerFixesConfig.networkAliasBridgeSecret),
                !ServerFixesConfig.networkAliasBridgeEnabled || CustomNameAliasBridge.isAvailable(),
                ServerFixesConfig.cobblemonHomeCrossServerBridgeEnabled,
                ServerFixesConfig.cobblemonHomeBridgeAckDelayTicks,
                ServerFixesConfig.disableSpawnerGlobalEntitySweep,
                ServerFixesConfig.blockVanillaMobsInNether,
                ServerFixesConfig.logBlockedVanillaMobsInNether,
                ServerFixesConfig.cobblemonCatchBehaviorBridgeEnabled,
                ServerFixesConfig.disableCobblemonCriticalCaptureRolls,
                ServerFixesConfig.normalizeCobblemonPokedexCatchPresentation,
                ServerFixesConfig.neutralizeExternalCatchRateModifiers,
                ServerFixesConfig.blockKantoNaturalSpawns,
                ServerFixesConfig.allowedKantoNaturalSpawnSpecies,
                ServerFixesConfig.luckPermsSecurityGuardEnabled,
                ServerFixesConfig.luckPermsLoginLoadTimeoutSeconds,
                ServerFixesConfig.travelersBackpackHuskSyncBridgeEnabled,
                ServerFixesConfig.travelersBackpackHuskSyncBridgeSeedMode,
                ServerFixesConfig.cobblemonPartyPcHuskSyncBridgeEnabled,
                ServerFixesConfig.cobblemonPartyPcHuskSyncBridgeSeedMode,
                ServerFixesConfig.adventureCameraStandItemIds,
                ServerFixesConfig.kantoDaycareBridgeEnabled,
                ServerFixesConfig.kantoDaycarePreventDittoOffspring,
                ServerFixesConfig.huntsItemBridgeEnabled,
                ServerFixesConfig.huntsItemId,
                ServerFixesConfig.huntsCommand
        );
    }
}
