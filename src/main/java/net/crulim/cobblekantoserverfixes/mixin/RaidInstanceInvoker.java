package net.crulim.cobblekantoserverfixes.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls Raid Dens' original hasFailed implementation when the CobbleKanto fix is
 * disabled, so disabling the config restores the upstream behavior exactly.
 */
@Mixin(targets = "com.necro.raid.dens.common.raids.RaidInstance", remap = false)
public interface RaidInstanceInvoker {
    @Invoker(value = "hasFailed", remap = false)
    boolean cobblekanto$invokeHasFailed(ServerPlayerEntity player);
}
