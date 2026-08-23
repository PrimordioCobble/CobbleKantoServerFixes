package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.CobblemonCatchBehaviorBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes Cobblemon 1.7.3's player-Pokédex dependency from critical-capture behavior.
 *
 * <p>The target is optional and unmapped because it belongs to Cobblemon. Both injections use
 * {@code require = 0}, so a future Cobblemon internal change cannot prevent the server from
 * starting; the bridge simply becomes inactive until its target is updated.</p>
 */
@Mixin(targets = "com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator", remap = false)
public abstract class CobblemonCaptureBehaviorMixin {
    @Inject(
            method = "shouldHaveCriticalCapture",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void cobblekanto$disableCriticalCaptureRolls(CallbackInfoReturnable<Boolean> cir) {
        if (CobblemonCatchBehaviorBridge.shouldDisableCriticalCaptureRolls()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "influence",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void cobblekanto$normalizePokedexCatchPresentation(CallbackInfoReturnable<Object> cir) {
        Object original = cir.getReturnValue();
        Object normalized = CobblemonCatchBehaviorBridge.normalizePokedexCatchPresentation(original);
        if (normalized != original) {
            cir.setReturnValue(normalized);
        }
    }
}
