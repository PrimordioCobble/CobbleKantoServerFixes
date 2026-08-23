package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.CobbleKantoServerFixes;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards Rad Gyms 0.4.4's PlayerSpawnHelper before it reaches the unsafe
 * `teleportTo(...) as ServerPlayer` cast.
 *
 * <p>Vanilla 1.21.1 returns null from ServerPlayerEntity#teleportTo when the
 * player entity is already removed. Rad Gyms force-casts that nullable result,
 * which crashes the server if a delayed gym return fires while the old player
 * entity is dead/removed.</p>
 *
 * <p>The same guard also suppresses stale delayed returns after a player has
 * already left the gym dimension through another teleport mechanism (for
 * example /spawn, a warp, or a Waystone), preventing the five-second return
 * callback from pulling them somewhere else afterwards.</p>
 */
@Mixin(targets = "lol.gito.radgyms.common.world.PlayerSpawnHelper", remap = false)
public abstract class RadGymsPlayerSpawnHelperCrashGuardMixin {
    private static final String RAD_GYMS_DIMENSION_ID = "rad_gyms:gym_dim";

    @Inject(
            method = "teleportPlayer(Lnet/minecraft/class_3222;Lnet/minecraft/class_3218;Lnet/minecraft/class_2338;FF)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cobblekanto$guardUnsafeOrStaleRadGymsTeleport(
            ServerPlayerEntity serverPlayer,
            ServerWorld targetWorld,
            BlockPos targetPos,
            float yaw,
            float pitch,
            CallbackInfo ci
    ) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.radGymsTeleportGuardEnabled) {
            return;
        }

        String reason = null;

        if (serverPlayer.isRemoved()) {
            reason = "player entity is removed";
        } else if (!serverPlayer.isAlive()) {
            reason = "player is not alive";
        } else {
            MinecraftServer server = targetWorld.getServer();
            ServerPlayerEntity currentPlayer = server.getPlayerManager().getPlayer(serverPlayer.getUuid());

            if (currentPlayer != serverPlayer) {
                reason = "player reference is stale";
            } else {
                String currentDimension = serverPlayer.getServerWorld().getRegistryKey().getValue().toString();
                String targetDimension = targetWorld.getRegistryKey().getValue().toString();
                boolean targetIsGym = RAD_GYMS_DIMENSION_ID.equals(targetDimension);
                boolean playerIsStillInGym = RAD_GYMS_DIMENSION_ID.equals(currentDimension);

                // PlayerSpawnHelper is used by Rad Gyms to enter the gym and to
                // perform the delayed return. A non-gym target while the player
                // is already outside the gym is therefore a stale return timer.
                if (!targetIsGym && !playerIsStillInGym) {
                    reason = "delayed gym return became stale because player already left the gym";
                }
            }
        }

        if (reason == null) {
            return;
        }

        if (ServerFixesConfig.logRadGymsTeleportGuard) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Blocked unsafe Rad Gyms teleport for player {}: {} (currentDimension={}, targetDimension={}, targetPos={}).",
                    serverPlayer.getGameProfile().getName(),
                    reason,
                    serverPlayer.getServerWorld().getRegistryKey().getValue(),
                    targetWorld.getRegistryKey().getValue(),
                    targetPos
            );
        }

        ci.cancel();
    }
}
