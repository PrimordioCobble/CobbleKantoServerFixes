package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

public final class HangingSignInteractionFix {
    private HangingSignInteractionFix() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }
            if (!ServerFixesConfig.enabled || !ServerFixesConfig.preventItemUseOnHangingSigns) {
                return ActionResult.PASS;
            }
            if (player instanceof ServerPlayerEntity serverPlayer
                    && ServerFixesConfig.preventItemUseOnHangingSignsAdventureOnly
                    && serverPlayer.interactionManager.getGameMode() != GameMode.ADVENTURE) {
                return ActionResult.PASS;
            }
            if (player.getStackInHand(hand).isEmpty()) {
                return ActionResult.PASS;
            }

            Identifier blockId = Registries.BLOCK.getId(world.getBlockState(hitResult.getBlockPos()).getBlock());
            if (blockId.getPath().contains("hanging_sign")) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }
}
