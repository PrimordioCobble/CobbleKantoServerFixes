package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.RaidParticipantRewardBridge;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks only Raid Dens' authoritative membership and lifecycle methods. The
 * original battle, damage, loot, capture, and arena implementations remain
 * untouched.
 */
@Mixin(targets = "com.necro.raid.dens.common.raids.RaidInstance", remap = false)
public abstract class RaidInstanceParticipantRewardMixin {
    @Inject(method = "addPlayer", at = @At("RETURN"), remap = false)
    private void cobblekanto$trackRaidParticipant(ServerPlayerEntity player, CallbackInfo ci) {
        RaidParticipantRewardBridge.trackParticipant(this, player);
    }

    @Inject(method = "handleSuccess", at = @At("HEAD"), remap = false)
    private void cobblekanto$prepareParticipantRewards(CallbackInfo ci) {
        RaidParticipantRewardBridge.prepareSuccessfulRaid(this);
    }

    @Inject(method = "handleSuccess", at = @At("TAIL"), remap = false)
    private void cobblekanto$completeParticipantRewards(CallbackInfo ci) {
        RaidParticipantRewardBridge.completeSuccessfulRaid(this);
    }

    // Both closeRaid overloads are intentionally matched. Clearing twice is
    // harmless, while this guarantees cleanup for success, failure, cancellation,
    // failed initialization, and explicit raid removal.
    @Inject(method = "closeRaid", at = @At("TAIL"), remap = false)
    private void cobblekanto$clearParticipantRewards(CallbackInfo ci) {
        RaidParticipantRewardBridge.clearRaid(this);
    }
}
