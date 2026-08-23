package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.RaidDensAutoAcceptBridge;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.necro.raid.dens.common.blocks.block.RaidCrystalBlock", remap = false)
public abstract class RaidCrystalBlockMixin {
    @Inject(method = "requestJoinRaid", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobblekanto$autoAcceptRaidJoinRequest(
            PlayerEntity player,
            @Coerce Object blockEntity,
            @Nullable ItemStack key,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.autoAcceptRaidJoinRequests) {
            return;
        }

        RaidDensAutoAcceptBridge.Result result = RaidDensAutoAcceptBridge.tryAutoAccept(player, blockEntity, key);
        if (result == RaidDensAutoAcceptBridge.Result.ACCEPTED) {
            cir.setReturnValue(true);
        } else if (result == RaidDensAutoAcceptBridge.Result.REJECTED) {
            cir.setReturnValue(false);
        }
    }
}