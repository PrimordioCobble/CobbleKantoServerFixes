package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.TournamentGen3BattleBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the synthetic aspectless "Legacy" forms from the tournament typing datapack
 * battle-compatible with Pokémon Showdown. Outside the Gen 3 tournament engine this mixin
 * is a no-op.
 */
@Mixin(targets = "com.cobblemon.mod.common.pokemon.Pokemon", remap = false)
public abstract class CobblemonPokemonLegacyShowdownIdMixin {
    @Inject(method = "showdownId", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void cobblekanto$normalizeLegacyShowdownId(CallbackInfoReturnable<String> cir) {
        String normalized = TournamentGen3BattleBridge.maybeNormalizeLegacyShowdownId(this);
        if (normalized != null) {
            cir.setReturnValue(normalized);
        }
    }
}
