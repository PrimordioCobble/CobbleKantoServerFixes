package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.TournamentGen3BattleBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compatibility guard for legacy Showdown generations.
 *
 * <p>Pokemon Showdown only includes the current {@code ability} field in side request data
 * in Gen 7+. Cobblemon 1.7.3 models that field as a Kotlin lateinit property and its
 * ShowdownPokemon#getAbility() getter throws when an old-generation request omits it.
 * The resulting exception happens while encoding cobblemon:battle_queue_request and
 * disconnects the player. Legacy generations still provide {@code baseAbility}, so it is
 * the correct safe client-side fallback.</p>
 *
 * <p>This mixin is intentionally unconditional: modern requests already initialize
 * {@code ability}, therefore their behavior is untouched. The guard only runs when
 * Cobblemon would otherwise throw because the field is null.</p>
 */
@Mixin(targets = "com.cobblemon.mod.common.battles.ShowdownPokemon", remap = false)
public abstract class CobblemonShowdownPokemonLegacyAbilityMixin {
    @Shadow(remap = false)
    private String ability;

    @Shadow(remap = false)
    private String baseAbility;

    @Inject(method = "getAbility", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void cobblekanto$legacyAbilityFallback(CallbackInfoReturnable<String> cir) {
        if (ability != null) {
            return;
        }

        String fallback = baseAbility;
        if (fallback == null || fallback.isBlank()) {
            fallback = "noability";
        }

        // Cache it on the request object so subsequent serialization/accesses are normal.
        ability = fallback;
        TournamentGen3BattleBridge.noteLegacyAbilityFallback(fallback);
        cir.setReturnValue(fallback);
    }
}
