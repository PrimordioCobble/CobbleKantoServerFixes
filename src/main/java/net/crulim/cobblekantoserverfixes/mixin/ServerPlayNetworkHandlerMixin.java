package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.DangerousCommandProtector;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Network-handler safeguards that must run before CobbleKanto's default-priority
 * movement mixin. The higher mixin priority is intentional: vanilla's
 * main-thread handoff must happen before any third-party callback can inspect
 * worlds or chunks from a Netty IO thread.
 */
@Mixin(value = ServerPlayNetworkHandler.class, priority = 2000)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), order = 900)
    private void cobblekanto_server_fixes$forcePlayerMoveOntoServerThread(
            PlayerMoveC2SPacket packet,
            CallbackInfo ci
    ) {
        if (!ServerFixesConfig.enabled || this.player == null) {
            return;
        }

        MinecraftServer server = this.player.getServer();
        if (server == null) {
            return;
        }

        /*
         * ServerPlayNetworkHandler#onPlayerMove already performs this handoff,
         * but CobbleKanto injects at HEAD and used to access the world before
         * vanilla reached it. Calling the same vanilla helper here is idempotent:
         * off-thread it schedules the packet and throws OffThreadException; on
         * the server thread it immediately returns.
         */
        NetworkThreadUtils.forceMainThread(
                packet,
                (ServerPlayNetworkHandler) (Object) this,
                server
        );
    }

    @Inject(method = "onCommandExecution", at = @At("HEAD"), cancellable = true)
    private void cobblekanto_server_fixes$blockDangerousPlayerCommands(
            CommandExecutionC2SPacket packet,
            CallbackInfo ci
    ) {
        if (DangerousCommandProtector.shouldBlock(this.player, packet.command())) {
            ci.cancel();
        }
    }
}
