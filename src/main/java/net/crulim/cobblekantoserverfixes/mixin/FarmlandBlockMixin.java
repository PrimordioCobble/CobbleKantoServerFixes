package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin extends Block {
    protected FarmlandBlockMixin(Settings settings) {
        super(settings);
    }

    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true)
    private void cobblekanto_server_fixes$preventTrampling(World world, BlockState state, BlockPos pos, Entity entity, float fallDistance, CallbackInfo ci) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.preventFarmlandTrampling) {
            return;
        }

        super.onLandedUpon(world, state, pos, entity, fallDistance);
        ci.cancel();
    }
}
