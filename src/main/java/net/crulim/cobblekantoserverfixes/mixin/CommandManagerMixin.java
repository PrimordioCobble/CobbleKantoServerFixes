package net.crulim.cobblekantoserverfixes.mixin;

import com.mojang.brigadier.ParseResults;
import net.crulim.cobblekantoserverfixes.DangerousCommandProtector;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {
    @Inject(method = "executeWithPrefix(Lnet/minecraft/server/command/ServerCommandSource;Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void cobblekanto_server_fixes$blockDangerousPrefixedCommands(ServerCommandSource source, String command, CallbackInfoReturnable<Integer> cir) {
        if (DangerousCommandProtector.shouldBlock(source, command)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "execute(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void cobblekanto_server_fixes$blockDangerousParsedCommands(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfoReturnable<Integer> cir) {
        if (DangerousCommandProtector.shouldBlock(parseResults.getContext().getSource(), command)) {
            cir.setReturnValue(0);
        }
    }
}
