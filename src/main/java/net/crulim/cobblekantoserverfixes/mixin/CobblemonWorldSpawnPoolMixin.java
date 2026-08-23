package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.KantoNaturalSpawnFilter;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the Survival-only Kanto filter as Cobblemon loads the world spawn pool.
 *
 * <p>The target is optional and unmapped because it belongs to Cobblemon rather than Minecraft.
 * The mixin configuration uses require=0, so this mod still starts when Cobblemon is absent or its
 * internals change; in that case no spawn filtering is attempted.</p>
 */
@Mixin(targets = "com.cobblemon.mod.common.api.spawning.detail.SpawnPool", remap = false)
public abstract class CobblemonWorldSpawnPoolMixin {
    @Inject(
            method = "onServerLoad",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void cobblekanto$filterKantoNaturalSpawns(MinecraftServer server, CallbackInfo ci) {
        KantoNaturalSpawnFilter.apply(this);
    }
}
