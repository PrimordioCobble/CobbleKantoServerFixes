package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Allows a player who has already lost a battle in the current raid to challenge
 * the same active raid boss again. Only the per-player failed-state check is
 * bypassed; every validation before and after it remains owned by Raid Dens.
 */
@Mixin(targets = "com.necro.raid.dens.common.network.packets.RaidChallengePacket", remap = false)
public abstract class RaidChallengePacketMixin {
    @Redirect(
            method = "handleServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/necro/raid/dens/common/raids/RaidInstance;hasFailed",
                    remap = false
            ),
            remap = false
    )
    private boolean cobblekanto$allowRaidRejoinAfterFaint(
            @Coerce Object raidInstance,
            ServerPlayerEntity player
    ) {
        if (ServerFixesConfig.enabled && ServerFixesConfig.allowRaidRejoinAfterFaint) {
            return false;
        }

        return ((RaidInstanceInvoker) raidInstance).cobblekanto$invokeHasFailed(player);
    }
}
