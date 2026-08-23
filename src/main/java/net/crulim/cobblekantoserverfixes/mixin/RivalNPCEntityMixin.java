package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.KantoNpcRivalStarterReflection;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.crulim.kantonpcs.entity.custom.RivalNPCEntity")
public abstract class RivalNPCEntityMixin {
    @Inject(method = "loadRivalTeamFromStorage", at = @At("HEAD"), remap = false)
    private void cobblekanto_server_fixes$scanStarterBeforeRivalTeam(ServerPlayerEntity player, CallbackInfo ci) {
        if (!ServerFixesConfig.rivalStarterScanBeforeRivalBattle) {
            return;
        }
        KantoNpcRivalStarterReflection.scanNow(player, "before_rival_team_load");
    }
}
