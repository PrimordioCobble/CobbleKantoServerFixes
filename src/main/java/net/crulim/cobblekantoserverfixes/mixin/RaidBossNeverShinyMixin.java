package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.RaidBossShinyGuardBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Forces Raid Dens boss entities to stay non-shiny. Reward/orb shiny rolls are handled separately. */
@Mixin(targets = "com.necro.raid.dens.common.data.raid.RaidBoss", remap = false)
public abstract class RaidBossNeverShinyMixin {
    @Inject(method = "getBossEntity", at = @At("RETURN"), remap = false, require = 0)
    private void cobblekanto$forceNormalRaidBoss(
            @Coerce Object world,
            Set<String> aspects,
            CallbackInfoReturnable<Object> cir
    ) {
        RaidBossShinyGuardBridge.forceNormalBossEntity(cir.getReturnValue());
    }
}
