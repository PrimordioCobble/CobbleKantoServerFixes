package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.LuckPermsSecurityBridge;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Security replacement for NeoAPI's LuckPerms provider.
 *
 * NeoAPI's original method throws when the player User is absent. This mixin
 * replaces the whole lookup with a fail-closed implementation: missing user,
 * exception or reflection failure always returns false. A true result requires
 * a real loaded LuckPerms grant or an already-OP level-4 player.
 */
@Pseudo
@Mixin(targets = "me.neovitalism.neoapi.permissions.LuckPermsPermissionProvider", remap = false, priority = 1500)
public abstract class NeoApiLuckPermsFailClosedMixin {
    @Inject(method = "hasPermission", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void cobblekanto_server_fixes$replacePermissionCheckFailClosed(
            ServerPlayerEntity player,
            String permission,
            int defaultLevel,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.luckPermsSecurityGuardEnabled) {
            return;
        }

        cir.setReturnValue(LuckPermsSecurityBridge.hasPermissionFailClosed(player, permission));
    }

    @Inject(method = "getMetaValue", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void cobblekanto_server_fixes$replaceMetaLookupFailClosed(
            ServerPlayerEntity player,
            String metaKey,
            CallbackInfoReturnable<String> cir
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.luckPermsSecurityGuardEnabled) {
            return;
        }

        cir.setReturnValue(LuckPermsSecurityBridge.getMetaValueFailClosed(player, metaKey));
    }
}
