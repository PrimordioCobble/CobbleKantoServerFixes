package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.RaidOrbShinyRateBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces CobbleKanto's RaidDens orb reward handler with a shiny_rate-aware server fix. */
@Mixin(targets = "net.crulim.cobblekanto.raids.RaidDensBridge", remap = false)
public abstract class CobbleKantoRaidDensBridgeMixin {
    @Inject(method = "handleRaidEndEvent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void cobblekanto$replaceRaidOrbRewardHandler(Object event, CallbackInfo ci) {
        if (RaidOrbShinyRateBridge.handleCobbleKantoRaidEndEvent(event)) {
            ci.cancel();
        }
    }
}
