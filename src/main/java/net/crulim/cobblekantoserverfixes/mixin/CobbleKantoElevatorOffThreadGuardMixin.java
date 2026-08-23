package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defense-in-depth for CobbleKanto 0.9.2's injected elevator callback.
 *
 * This mixin deliberately has a lower priority than CobbleKanto's default
 * priority, so the callback method has already been merged into
 * ServerPlayNetworkHandler when this optional guard is applied. It contains no
 * compile-time reference to CobbleKanto classes and therefore remains fully
 * server-side.
 */
@Mixin(value = ServerPlayNetworkHandler.class, priority = 500)
public abstract class CobbleKantoElevatorOffThreadGuardMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(
            method = "cobblekanto$elevatorJump",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void cobblekanto_server_fixes$cancelElevatorCallbackOffThread(CallbackInfo ci) {
        if (!ServerFixesConfig.enabled) {
            return;
        }

        MinecraftServer server = this.player == null ? null : this.player.getServer();
        if (server == null || !server.isOnThread()) {
            ci.cancel();
        }
    }
}
