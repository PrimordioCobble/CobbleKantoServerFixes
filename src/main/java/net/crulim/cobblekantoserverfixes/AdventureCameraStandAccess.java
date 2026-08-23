package net.crulim.cobblekantoserverfixes;

import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class AdventureCameraStandAccess {
    private AdventureCameraStandAccess() {
    }

    public static boolean isAllowedItem(ItemStack stack) {
        if (!isEnabled() || stack == null || stack.isEmpty()) {
            return false;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        return itemId != null && ServerFixesConfig.adventureCameraStandItemIds.contains(itemId.toString());
    }

    public static boolean shouldBypassAdventureCanPlaceOn(ItemStack stack, CachedBlockPosition clickedBlock) {
        return clickedBlock != null && isAllowedItem(stack);
    }

    public static boolean shouldAllowAdventurePlacement(PlayerEntity player, ItemStack stack) {
        return player != null
                && !player.isSpectator()
                && !player.canModifyBlocks()
                && isAllowedItem(stack);
    }

    private static boolean isEnabled() {
        return ServerFixesConfig.enabled && ServerFixesConfig.allowExposureCameraStandInAdventure;
    }
}
