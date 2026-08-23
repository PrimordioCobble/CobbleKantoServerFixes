package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.KantoDaycareBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties", remap = false)
public abstract class KantoDaycarePotentialPokemonPropertiesMixin {
    @Inject(method = "getForm", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobblekanto$forceKantoForm(CallbackInfoReturnable<Object> cir) {
        Object replacementForm = KantoDaycareBridge.getKantoReplacementFormForPotential(this);
        if (replacementForm != null) {
            cir.setReturnValue(replacementForm);
        }
    }

    @Inject(method = "getSpecies", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobblekanto$forceKantoSpecies(CallbackInfoReturnable<Object> cir) {
        Object replacementForm = KantoDaycareBridge.getKantoReplacementFormForPotential(this);
        Object replacementSpecies = KantoDaycareBridge.getSpeciesFromForm(replacementForm);
        if (replacementSpecies != null) {
            cir.setReturnValue(replacementSpecies);
        }
    }

    @Inject(method = "createPokemonProperties", at = @At("RETURN"), cancellable = false, remap = false)
    private void cobblekanto$forceKantoFinalEggProperties(CallbackInfoReturnable<Object> cir) {
        KantoDaycareBridge.forceKantoOnlyPropertiesForPotential(this, cir.getReturnValue());
    }
}
