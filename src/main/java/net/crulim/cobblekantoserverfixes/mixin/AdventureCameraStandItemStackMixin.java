package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.AdventureCameraStandAccess;
import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class AdventureCameraStandItemStackMixin {
    @Inject(method = "canPlaceOn", at = @At("HEAD"), cancellable = true)
    private void cobblekantoServerFixes$allowCameraStandPlacementInAdventure(
            CachedBlockPosition clickedBlock,
            CallbackInfoReturnable<Boolean> cir
    ) {
        ItemStack stack = (ItemStack) (Object) this;
        if (AdventureCameraStandAccess.shouldBypassAdventureCanPlaceOn(stack, clickedBlock)) {
            cir.setReturnValue(true);
        }
    }
}
