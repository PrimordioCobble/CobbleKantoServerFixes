package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.CobbleKantoServerFixes;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.village.VillagerType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Backports the null guard for type-aware villager trades to 1.21.1.
 *
 * <p>Mods may register custom {@link VillagerType}s that are not represented in vanilla's
 * type-aware trade map. Vanilla tries to build the buying item before it can reject the
 * missing mapping, which crashes when the map returns {@code null}. Returning {@code null}
 * from the trade factory is the supported "no offer" result, so we cancel before the item
 * constructor is reached.</p>
 */
@Mixin(targets = "net.minecraft.village.TradeOffers$TypeAwareBuyForOneEmeraldFactory")
public abstract class TypeAwareVillagerTradeCrashGuardMixin {
    @Shadow
    @Final
    private Map<VillagerType, Item> map;

    @Unique
    private static boolean cobblekanto_server_fixes$loggedMissingVillagerTypeTrade;

    @Inject(method = "create", at = @At("HEAD"), cancellable = true, require = 1)
    private void cobblekanto_server_fixes$skipMissingVillagerTypeTrade(
            Entity entity,
            Random random,
            CallbackInfoReturnable<TradeOffer> cir
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.preventTypeAwareVillagerTradeCrash) {
            return;
        }

        if (!(entity instanceof VillagerDataContainer villager)) {
            return;
        }

        VillagerType villagerType = villager.getVillagerData().getType();
        if (this.map.get(villagerType) != null) {
            return;
        }

        if (!cobblekanto_server_fixes$loggedMissingVillagerTypeTrade) {
            cobblekanto_server_fixes$loggedMissingVillagerTypeTrade = true;
            CobbleKantoServerFixes.LOGGER.warn(
                    "Prevented a type-aware villager trade crash for villager type {}. "
                            + "The unsupported offer was skipped and the villager can continue normally.",
                    villagerType
            );
        }

        cir.setReturnValue(null);
    }
}
