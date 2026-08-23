package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.CobbleKantoServerFixes;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.crulim.kantospawners.util.SpawnerPokemonTracker", remap = false)
public abstract class KantoSpawnersGlobalSweepMixin {
    private static boolean cobblekanto$loggedGlobalSweepSuppression;

    @Inject(
            method = "sweepWorld",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void cobblekanto$disableGlobalEntitySweep(ServerWorld world, CallbackInfo ci) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.disableSpawnerGlobalEntitySweep) {
            return;
        }

        if (!cobblekanto$loggedGlobalSweepSuppression) {
            cobblekanto$loggedGlobalSweepSuppression = true;
            CobbleKantoServerFixes.LOGGER.warn(
                    "Disabled KantoSpawners' full-world spawner Pokémon sweep. "
                            + "Per-spawner cleanup remains active."
            );
        }

        ci.cancel();
    }
}
