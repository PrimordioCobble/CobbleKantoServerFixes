package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(
            method = "broadcast(Lnet/minecraft/text/Text;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cobblekanto_server_fixes$suppressBackendJoinLeaveMessages(
            Text message,
            boolean overlay,
            CallbackInfo ci
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.suppressBackendJoinLeaveMessages) {
            return;
        }

        if (!(message.getContent() instanceof TranslatableTextContent translatable)) {
            return;
        }

        String key = translatable.getKey();
        if ("multiplayer.player.joined".equals(key)
                || "multiplayer.player.joined.renamed".equals(key)
                || "multiplayer.player.left".equals(key)) {
            ci.cancel();
        }
    }
}
