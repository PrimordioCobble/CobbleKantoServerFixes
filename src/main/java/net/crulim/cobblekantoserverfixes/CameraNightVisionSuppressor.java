package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Locale;

public final class CameraNightVisionSuppressor {
    private static final String COBBLEKANTO_NIGHT_VISION_OFF_TAG = "cobblekanto:nv_off";
    private static final String TEMP_CAMERA_NIGHT_VISION_OFF_TAG = "cobblekanto_server_fixes:camera_nv_off";

    private CameraNightVisionSuppressor() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ServerFixesConfig.enabled || !ServerFixesConfig.suppressNightVisionWhileHoldingCamera) {
                clearTemporaryCameraTags(server);
                return;
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                boolean holdingCamera = isHoldingConfiguredCamera(player);

                if (holdingCamera) {
                    if (!player.getCommandTags().contains(COBBLEKANTO_NIGHT_VISION_OFF_TAG)) {
                        player.addCommandTag(COBBLEKANTO_NIGHT_VISION_OFF_TAG);
                        player.addCommandTag(TEMP_CAMERA_NIGHT_VISION_OFF_TAG);
                    }

                    if (player.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
                        player.removeStatusEffect(StatusEffects.NIGHT_VISION);
                    }
                    continue;
                }

                if (player.getCommandTags().contains(TEMP_CAMERA_NIGHT_VISION_OFF_TAG)) {
                    player.removeCommandTag(TEMP_CAMERA_NIGHT_VISION_OFF_TAG);
                    player.removeCommandTag(COBBLEKANTO_NIGHT_VISION_OFF_TAG);
                }
            }
        });
    }

    private static void clearTemporaryCameraTags(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getCommandTags().contains(TEMP_CAMERA_NIGHT_VISION_OFF_TAG)) {
                player.removeCommandTag(TEMP_CAMERA_NIGHT_VISION_OFF_TAG);
                player.removeCommandTag(COBBLEKANTO_NIGHT_VISION_OFF_TAG);
            }
        }
    }

    private static boolean isHoldingConfiguredCamera(ServerPlayerEntity player) {
        return isConfiguredCameraItem(player.getMainHandStack()) || isConfiguredCameraItem(player.getOffHandStack());
    }

    private static boolean isConfiguredCameraItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        String fullId = itemId.toString().toLowerCase(Locale.ROOT);
        String path = itemId.getPath().toLowerCase(Locale.ROOT);

        for (String configuredId : ServerFixesConfig.nightVisionCameraItemIds) {
            if (fullId.equals(configuredId)) {
                return true;
            }
        }

        for (String keyword : ServerFixesConfig.nightVisionCameraItemPathKeywords) {
            if (!keyword.isBlank() && (path.contains(keyword) || fullId.contains(keyword))) {
                return true;
            }
        }

        return false;
    }
}
