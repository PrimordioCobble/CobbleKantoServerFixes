package net.crulim.cobblekantoserverfixes.mixin;

import com.mojang.authlib.GameProfile;
import net.crulim.cobblekantoserverfixes.LuckPermsSecurityBridge;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.config.ReadyC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Prevents the configuration -> play transition until LuckPerms confirms that
 * the player's User object is loaded. The server never admits the player with
 * unknown permission state.
 */
@Mixin(value = ServerConfigurationNetworkHandler.class, priority = 1500)
public abstract class ServerConfigurationNetworkHandlerLuckPermsMixin extends ServerCommonNetworkHandler {
    @Shadow
    @Final
    private GameProfile profile;

    @Unique
    private CompletableFuture<Boolean> cobblekanto_server_fixes$luckPermsLoadFuture;

    @Unique
    private ReadyC2SPacket cobblekanto_server_fixes$pendingReadyPacket;

    @Unique
    private boolean cobblekanto_server_fixes$permissionDataConfirmed;

    @Unique
    private boolean cobblekanto_server_fixes$terminalFailure;

    protected ServerConfigurationNetworkHandlerLuckPermsMixin(
            MinecraftServer server,
            ClientConnection connection,
            ConnectedClientData clientData
    ) {
        super(server, connection, clientData);
    }

    @Inject(method = "onReady", at = @At("HEAD"), cancellable = true, require = 1)
    private void cobblekanto_server_fixes$waitForLuckPermsUser(ReadyC2SPacket packet, CallbackInfo ci) {
        if (!ServerFixesConfig.enabled
                || !ServerFixesConfig.luckPermsSecurityGuardEnabled
                || !LuckPermsSecurityBridge.isLuckPermsInstalled()) {
            return;
        }

        if (this.cobblekanto_server_fixes$permissionDataConfirmed) {
            return;
        }

        // Never let vanilla/other mods continue into PlayerManager while the
        // permission state is unknown.
        ci.cancel();
        if (this.cobblekanto_server_fixes$terminalFailure) {
            return;
        }

        this.cobblekanto_server_fixes$pendingReadyPacket = packet;
        if (this.cobblekanto_server_fixes$luckPermsLoadFuture != null) {
            return;
        }

        LuckPermsSecurityBridge.logLoginHeld(this.profile.getId(), this.profile.getName());
        int timeoutSeconds = Math.max(5, Math.min(60, ServerFixesConfig.luckPermsLoginLoadTimeoutSeconds));
        this.cobblekanto_server_fixes$luckPermsLoadFuture = LuckPermsSecurityBridge
                .loadUserFailClosed(this.profile.getId())
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS);

        this.cobblekanto_server_fixes$luckPermsLoadFuture.whenComplete((loaded, throwable) -> this.server.execute(() -> {
            this.cobblekanto_server_fixes$luckPermsLoadFuture = null;
            if (!this.isConnectionOpen() || this.cobblekanto_server_fixes$terminalFailure) {
                return;
            }

            boolean safelyLoaded = throwable == null
                    && Boolean.TRUE.equals(loaded)
                    && LuckPermsSecurityBridge.isUserLoaded(this.profile.getId());
            if (!safelyLoaded) {
                this.cobblekanto_server_fixes$terminalFailure = true;
                LuckPermsSecurityBridge.logLoginDenied(this.profile.getId(), this.profile.getName(), throwable);
                this.disconnect(Text.literal(
                        "[LP] Seus dados de permissão não puderam ser carregados com segurança. "
                                + "Tente entrar novamente em alguns segundos."
                ));
                return;
            }

            this.cobblekanto_server_fixes$permissionDataConfirmed = true;
            LuckPermsSecurityBridge.logLoginReleased(this.profile.getId(), this.profile.getName());

            ReadyC2SPacket pendingPacket = this.cobblekanto_server_fixes$pendingReadyPacket;
            this.cobblekanto_server_fixes$pendingReadyPacket = null;
            if (pendingPacket != null) {
                ((ServerConfigurationNetworkHandler) (Object) this).onReady(pendingPacket);
            }
        }));
    }
}
