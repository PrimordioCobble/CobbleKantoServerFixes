package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.TournamentGen3BattleBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Optional Cobblemon 1.7.3 hook that swaps only the BattleFormat constructor argument.
 *
 * <p>Targeting BattleRegistry.startBattle keeps the override at the last safe point before
 * PokemonBattle is created and before Showdown receives the format JSON. require=0 preserves
 * the ServerFixes fail-open startup behavior if Cobblemon internals ever move.</p>
 */
@Mixin(targets = "com.cobblemon.mod.common.battles.BattleRegistry", remap = false)
public abstract class CobblemonBattleRegistryGen3Mixin {
    @ModifyArgs(
            method = "startBattle",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;<init>(Lcom/cobblemon/mod/common/battles/BattleFormat;Lcom/cobblemon/mod/common/battles/BattleSide;Lcom/cobblemon/mod/common/battles/BattleSide;)V",
                    remap = false
            ),
            require = 0,
            remap = false
    )
    private static void cobblekanto$applyTournamentGen3Format(Args args) {
        Object originalFormat = args.get(0);
        Object side1 = args.get(1);
        Object side2 = args.get(2);
        Object rewritten = TournamentGen3BattleBridge.maybeRewriteFormat(originalFormat, side1, side2);
        if (rewritten != originalFormat) {
            args.set(0, rewritten);
        }
    }
}
