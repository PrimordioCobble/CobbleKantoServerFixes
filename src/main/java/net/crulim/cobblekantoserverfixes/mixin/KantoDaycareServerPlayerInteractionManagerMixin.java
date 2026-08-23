package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.KantoDaycareBridge;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public abstract class KantoDaycareServerPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void cobblekanto$allowDaycareIncubatorOnPastureInAdventure(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        ActionResult result = KantoDaycareBridge.tryHandleAdventureIncubatorUse(player, world, hand, hitResult);
        if (result != ActionResult.PASS) {
            cir.setReturnValue(result);
        }
    }
}
