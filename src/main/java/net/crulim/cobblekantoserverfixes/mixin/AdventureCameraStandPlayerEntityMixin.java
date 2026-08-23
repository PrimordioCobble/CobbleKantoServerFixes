package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.AdventureCameraStandAccess;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class AdventureCameraStandPlayerEntityMixin {
    @Inject(method = "canPlaceOn", at = @At("HEAD"), cancellable = true)
    private void cobblekantoServerFixes$allowCameraStandPlacementInAdventure(
            BlockPos clickedPos,
            Direction clickedFace,
            ItemStack stack,
            CallbackInfoReturnable<Boolean> cir
    ) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (AdventureCameraStandAccess.shouldAllowAdventurePlacement(player, stack)) {
            cir.setReturnValue(true);
        }
    }
}
