package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Removes vanilla mobs whenever they are loaded into the Nether.
 *
 * <p>This deliberately uses Fabric's entity-load lifecycle event instead of scanning every entity
 * every tick. It therefore catches vanilla mobs created by normal spawning, structure generation,
 * monster spawners, commands/mod code, chunk reloads, and cross-dimension transfers as soon as the
 * entity is attached to a {@link ServerWorld}.</p>
 *
 * <p>Only {@link MobEntity MobEntities} whose registered entity type belongs to the
 * {@code minecraft} namespace are removed. Players, armor stands, item frames, projectiles,
 * vehicles, dropped items, Cobblemon Pokémon, and modded NPC/mob entity types are left alone.</p>
 */
public final class VanillaNetherMobGuard {
    private static final String VANILLA_NAMESPACE = "minecraft";

    private VanillaNetherMobGuard() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register(VanillaNetherMobGuard::onEntityLoad);
    }

    private static void onEntityLoad(Entity entity, ServerWorld world) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.blockVanillaMobsInNether) {
            return;
        }
        if (!World.NETHER.equals(world.getRegistryKey())) {
            return;
        }
        if (!(entity instanceof MobEntity)) {
            return;
        }

        Identifier entityTypeId = Registries.ENTITY_TYPE.getId(entity.getType());
        if (!VANILLA_NAMESPACE.equals(entityTypeId.getNamespace())) {
            return;
        }

        if (ServerFixesConfig.logBlockedVanillaMobsInNether) {
            CobbleKantoServerFixes.LOGGER.info(
                    "Discarding vanilla Nether mob type={} name={} customName={} pos={} {} {}.",
                    entityTypeId,
                    entity.getName().getString(),
                    entity.hasCustomName() ? entity.getCustomName().getString() : "<none>",
                    entity.getBlockX(),
                    entity.getBlockY(),
                    entity.getBlockZ()
            );
        }

        // discard() removes the entity without death processing, drops, XP, or kill side effects.
        entity.discard();
    }
}
