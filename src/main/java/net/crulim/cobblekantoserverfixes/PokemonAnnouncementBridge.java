package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-only rare Pokémon announcements.
 *
 * Uses Cobblemon's public event observables through reflection so ServerFixes can remain an
 * optional/server-only compatibility mod without adding a hard compile/runtime dependency.
 */
public final class PokemonAnnouncementBridge {
    private static final String COBBLEMON_EVENTS_CLASS = "com.cobblemon.mod.common.api.events.CobblemonEvents";
    private static final String POKEMON_ENTITY_CLASS = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity";
    private static final String CORE_SHINY_CONFIG_CLASS = "net.crulim.cobblekanto.config.ShinyNotificationConfig";
    private static final String COBBLESAFARI_INCUBATOR_ID = "cobblesafari:incubator";
    private static final int INCUBATOR_SCAN_RADIUS_XZ = 5;
    private static final int INCUBATOR_SCAN_RADIUS_Y = 3;
    private static final long RARE_DEFEAT_DEDUP_MILLIS = 15_000L;

    private static final Map<UUID, Long> RECENT_RARE_DEFEATS = new HashMap<>();

    private static boolean registered;
    private static MinecraftServer activeServer;
    private static boolean coreNotifierTakeoverLogged;

    private PokemonAnnouncementBridge() {
    }

    public static void register() {
        if (registered || !ServerFixesConfig.enabled || !ServerFixesConfig.pokemonAnnouncementBridgeEnabled) {
            return;
        }
        registered = true;

        if (!FabricLoader.getInstance().isModLoaded("cobblemon")) {
            CobbleKantoServerFixes.LOGGER.info("Cobblemon is not loaded; Pokémon announcement bridge was not registered.");
            return;
        }

        try {
            subscribe("POKEMON_CAPTURED", PokemonAnnouncementBridge::onPokemonCaptured);
            subscribe("HATCH_EGG_POST", PokemonAnnouncementBridge::onEggHatched);
            subscribe("FOSSIL_REVIVED", PokemonAnnouncementBridge::onFossilRevived);
            subscribe("POKEMON_GAINED", PokemonAnnouncementBridge::onPokemonGained);
            subscribe("BATTLE_FAINTED", PokemonAnnouncementBridge::onBattleFainted);
            ServerLivingEntityEvents.AFTER_DEATH.register(PokemonAnnouncementBridge::onLivingEntityDeath);

            // By SERVER_STARTED every mod initializer has run. This prevents the old CobbleKanto
            // shiny-capture subscriber from emitting a second message without modifying the Core jar.
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                activeServer = server;
                disableLegacyCoreShinyNotifier();
            });
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> activeServer = null);

            CobbleKantoServerFixes.LOGGER.info(
                    "Pokémon announcement bridge registered (capture={}, hatch={}, fossil={}, incubator={}, legendaryCapture={}, mythicalCapture={}, legendaryDefeat={}, mythicalDefeat={}, shinyDefeat={}).",
                    ServerFixesConfig.announceShinyCaptures,
                    ServerFixesConfig.announceShinyHatches,
                    ServerFixesConfig.announceShinyFossilRevives,
                    ServerFixesConfig.announceShinyIncubatorRewards,
                    ServerFixesConfig.announceLegendaryCaptures,
                    ServerFixesConfig.announceMythicalCaptures,
                    ServerFixesConfig.announceLegendaryDefeats,
                    ServerFixesConfig.announceMythicalDefeats,
                    ServerFixesConfig.announceShinyDefeats
            );
        } catch (Throwable throwable) {
            CobbleKantoServerFixes.LOGGER.error("Failed to register Pokémon announcement bridge.", throwable);
        }
    }

    private static void onPokemonCaptured(Object event) {
        if (!isEnabled()) {
            return;
        }

        ServerPlayerEntity player = extractPlayer(event);
        Object pokemon = extractPokemon(event);
        if (player == null || pokemon == null) {
            return;
        }

        boolean shiny = isShiny(pokemon);
        boolean legendary = isLegendary(pokemon);
        boolean mythical = isMythical(pokemon);

        if (shiny && legendary && ServerFixesConfig.announceShinyCaptures && ServerFixesConfig.announceLegendaryCaptures) {
            broadcast(player, shinyLegendaryCaptureMessage(player, pokemon));
            return;
        }
        if (shiny && mythical && ServerFixesConfig.announceShinyCaptures && ServerFixesConfig.announceMythicalCaptures) {
            broadcast(player, shinyMythicalCaptureMessage(player, pokemon));
            return;
        }
        if (legendary && ServerFixesConfig.announceLegendaryCaptures) {
            broadcast(player, legendaryCaptureMessage(player, pokemon));
            return;
        }
        if (mythical && ServerFixesConfig.announceMythicalCaptures) {
            broadcast(player, mythicalCaptureMessage(player, pokemon));
            return;
        }
        if (shiny && ServerFixesConfig.announceShinyCaptures) {
            broadcast(player, shinyCaptureMessage(player, pokemon));
        }
    }

    /**
     * Announces a wild Legendary, Mythical, or Shiny defeat in a Cobblemon battle.
     *
     * BATTLE_FAINTED is used instead of entity death because battle fainting is resolved by
     * Showdown/Cobblemon and does not reliably carry a vanilla DamageSource.
     */
    private static void onBattleFainted(Object event) {
        if (!isEnabled()
                || (!ServerFixesConfig.announceLegendaryDefeats
                && !ServerFixesConfig.announceMythicalDefeats
                && !ServerFixesConfig.announceShinyDefeats)) {
            return;
        }

        Object killed = invokeNoArgsQuietly(event, "getKilled");
        Object pokemon = invokeNoArgsQuietly(killed, "getEffectedPokemon");
        if (pokemon == null) {
            pokemon = invokeNoArgsQuietly(killed, "getOriginalPokemon");
        }
        if (pokemon == null) {
            return;
        }

        boolean shiny = isShiny(pokemon);
        boolean legendary = isLegendary(pokemon);
        boolean mythical = isMythical(pokemon);
        boolean announceShiny = shiny && ServerFixesConfig.announceShinyDefeats;
        boolean announceLegendary = legendary && ServerFixesConfig.announceLegendaryDefeats;
        boolean announceMythical = mythical && ServerFixesConfig.announceMythicalDefeats;
        if (!announceShiny && !announceLegendary && !announceMythical) {
            return;
        }

        Object killedActor = invokeNoArgsQuietly(killed, "getActor");
        if (!actorTypeIs(killedActor, "WILD")) {
            return;
        }

        ServerPlayerEntity player = resolveBattleDefeater(event);
        if (player == null || !markRareDefeatOnce(pokemon)) {
            return;
        }

        broadcastDefeat(player, pokemon, announceShiny, announceLegendary, announceMythical);
    }

    /**
     * Covers a wild Pokémon being killed directly in the Minecraft world, such as a player
     * punching/hitting it outside a Cobblemon battle. Projectile kills also work because
     * DamageSource#getAttacker resolves the owning player.
     */
    private static void onLivingEntityDeath(LivingEntity entity, DamageSource damageSource) {
        if (!isEnabled()
                || (!ServerFixesConfig.announceLegendaryDefeats
                && !ServerFixesConfig.announceMythicalDefeats
                && !ServerFixesConfig.announceShinyDefeats)) {
            return;
        }
        if (!isInstanceOfNamedClass(entity, POKEMON_ENTITY_CLASS)) {
            return;
        }

        /*
         * Only a real player-caused world kill is announced.
         * Vanilla /kill uses a generic kill DamageSource with no player attacker,
         * so admin/OP cleanup is intentionally silent.
         */
        Entity attacker = damageSource.getAttacker();
        if (!(attacker instanceof ServerPlayerEntity player)) {
            return;
        }

        Object pokemon = invokeNoArgsQuietly(entity, "getPokemon");
        if (pokemon == null || !isWildPokemon(pokemon)) {
            return;
        }

        boolean shiny = isShiny(pokemon);
        boolean legendary = isLegendary(pokemon);
        boolean mythical = isMythical(pokemon);
        boolean announceShiny = shiny && ServerFixesConfig.announceShinyDefeats;
        boolean announceLegendary = legendary && ServerFixesConfig.announceLegendaryDefeats;
        boolean announceMythical = mythical && ServerFixesConfig.announceMythicalDefeats;
        if ((!announceShiny && !announceLegendary && !announceMythical) || !markRareDefeatOnce(pokemon)) {
            return;
        }

        broadcastDefeat(player, pokemon, announceShiny, announceLegendary, announceMythical);
    }

    private static ServerPlayerEntity resolveBattleDefeater(Object event) {
        Object context = invokeNoArgsQuietly(event, "getContext");
        Object origin = invokeNoArgsQuietly(context, "getOrigin");
        Object originActor = invokeNoArgsQuietly(origin, "getActor");

        ServerPlayerEntity direct = playerFromBattleActor(originActor);
        if (direct != null) {
            return direct;
        }

        // Fallback for residual/self-damage contexts where Cobblemon cannot expose a direct
        // origin: if this is a normal PvE battle with exactly one player actor, that player
        // is the only sensible recipient of the defeat credit.
        Object battle = invokeNoArgsQuietly(event, "getBattle");
        Object actors = invokeNoArgsQuietly(battle, "getActors");
        if (!(actors instanceof Iterable<?> iterable)) {
            return null;
        }

        ServerPlayerEntity onlyPlayer = null;
        for (Object actor : iterable) {
            if (!actorTypeIs(actor, "PLAYER")) {
                continue;
            }
            ServerPlayerEntity candidate = playerFromBattleActor(actor);
            if (candidate == null) {
                continue;
            }
            if (onlyPlayer != null && !onlyPlayer.getUuid().equals(candidate.getUuid())) {
                return null;
            }
            onlyPlayer = candidate;
        }
        return onlyPlayer;
    }

    private static ServerPlayerEntity playerFromBattleActor(Object actor) {
        if (actor == null || !actorTypeIs(actor, "PLAYER")) {
            return null;
        }

        Object entity = invokeNoArgsQuietly(actor, "getEntity");
        if (entity instanceof ServerPlayerEntity player) {
            return player;
        }

        Object uuid = invokeNoArgsQuietly(actor, "getUuid");
        if (uuid instanceof UUID playerId && activeServer != null) {
            return activeServer.getPlayerManager().getPlayer(playerId);
        }
        return null;
    }

    private static boolean actorTypeIs(Object actor, String expected) {
        Object type = invokeNoArgsQuietly(actor, "getType");
        return type != null && expected.equalsIgnoreCase(type.toString());
    }

    private static boolean isWildPokemon(Object pokemon) {
        Object wild = invokeNoArgsQuietly(pokemon, "isWild");
        if (wild instanceof Boolean bool) {
            return bool;
        }

        Object ownerUuid = invokeNoArgsQuietly(pokemon, "getOwnerUUID");
        if (ownerUuid == null) {
            ownerUuid = invokeNoArgsQuietly(pokemon, "getOwnerUuid");
        }
        return ownerUuid == null;
    }

    private static boolean isInstanceOfNamedClass(Object target, String className) {
        if (target == null) {
            return false;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            if (className.equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean markRareDefeatOnce(Object pokemon) {
        Object uuidValue = invokeNoArgsQuietly(pokemon, "getUuid");
        if (!(uuidValue instanceof UUID pokemonId)) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long previous = RECENT_RARE_DEFEATS.put(pokemonId, now);
        if (RECENT_RARE_DEFEATS.size() > 256) {
            RECENT_RARE_DEFEATS.entrySet().removeIf(
                    entry -> now - entry.getValue() > RARE_DEFEAT_DEDUP_MILLIS
            );
        }
        return previous == null || now - previous > RARE_DEFEAT_DEDUP_MILLIS;
    }

    private static void onEggHatched(Object event) {
        if (!isEnabled() || !ServerFixesConfig.announceShinyHatches) {
            return;
        }

        ServerPlayerEntity player = extractPlayer(event);
        Object pokemon = extractPokemon(event);
        if (player == null || pokemon == null || !isShiny(pokemon)) {
            return;
        }

        broadcast(player, shinyHatchMessage(player, pokemon));
    }

    private static void onFossilRevived(Object event) {
        if (!isEnabled() || !ServerFixesConfig.announceShinyFossilRevives) {
            return;
        }

        ServerPlayerEntity player = extractPlayer(event);
        Object pokemon = extractPokemon(event);
        if (player == null || pokemon == null || !isShiny(pokemon)) {
            return;
        }

        broadcast(player, shinyFossilMessage(player, pokemon));
    }

    /**
     * CobbleSafari's Incubator currently creates the Pokémon and calls party.add(pokemon)
     * directly instead of emitting Cobblemon's HATCH_EGG_POST. POKEMON_GAINED is therefore
     * used only as a narrowly-scoped fallback: a shiny gain is announced here exclusively
     * while the player is next to a completed CobbleSafari Incubator.
     */
    private static void onPokemonGained(Object event) {
        if (!isEnabled() || !ServerFixesConfig.announceShinyIncubatorRewards) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("cobblesafari")) {
            return;
        }

        Object pokemon = extractPokemon(event);
        ServerPlayerEntity player = extractPlayerFromGainedEvent(event);
        if (player == null || pokemon == null || !isShiny(pokemon)) {
            return;
        }
        if (!isNearCompletedCobbleSafariIncubator(player)) {
            return;
        }

        broadcast(player, shinyIncubatorMessage(player, pokemon));
    }

    private static boolean isEnabled() {
        return ServerFixesConfig.enabled && ServerFixesConfig.pokemonAnnouncementBridgeEnabled;
    }

    private static void broadcast(ServerPlayerEntity player, Text message) {
        if (player.getServer() != null) {
            player.getServer().getPlayerManager().broadcast(message, false);
        }
    }

    private static void broadcastDefeat(
            ServerPlayerEntity player,
            Object pokemon,
            boolean shiny,
            boolean legendary,
            boolean mythical
    ) {
        if (shiny && legendary) {
            broadcast(player, shinyLegendaryDefeatMessage(player, pokemon));
        } else if (shiny && mythical) {
            broadcast(player, shinyMythicalDefeatMessage(player, pokemon));
        } else if (legendary) {
            broadcast(player, legendaryDefeatMessage(player, pokemon));
        } else if (mythical) {
            broadcast(player, mythicalDefeatMessage(player, pokemon));
        } else if (shiny) {
            broadcast(player, shinyDefeatMessage(player, pokemon));
        }
    }

    private static MutableText shinyCaptureMessage(ServerPlayerEntity player, Object pokemon) {
        MutableText playerName = playerName(player, Formatting.LIGHT_PURPLE);
        MutableText pokemonName = pokemonName(pokemon, Formatting.YELLOW);
        MutableText shiny = Text.literal("SHINY").formatted(Formatting.GOLD);

        return Text.empty()
                .append(playerName)
                .append(Text.literal(" acabou de capturar um ").formatted(Formatting.DARK_PURPLE))
                .append(pokemonName)
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(shiny)
                .append(Text.literal("!").formatted(Formatting.DARK_PURPLE));
    }

    private static MutableText legendaryCaptureMessage(ServerPlayerEntity player, Object pokemon) {
        MutableText playerName = playerName(player, Formatting.AQUA);
        MutableText pokemonName = pokemonName(pokemon, Formatting.GOLD);
        MutableText legendary = Text.literal("LENDÁRIO").formatted(Formatting.YELLOW);

        return Text.empty()
                .append(playerName)
                .append(Text.literal(" acaba de capturar ").formatted(Formatting.DARK_AQUA))
                .append(pokemonName)
                .append(Text.literal(" ").formatted(Formatting.DARK_AQUA))
                .append(legendary)
                .append(Text.literal("!").formatted(Formatting.DARK_AQUA));
    }

    private static MutableText mythicalCaptureMessage(ServerPlayerEntity player, Object pokemon) {
        MutableText playerName = playerName(player, Formatting.AQUA);
        MutableText pokemonName = pokemonName(pokemon, Formatting.LIGHT_PURPLE);
        MutableText mythical = Text.literal("MÍTICO").formatted(Formatting.AQUA);

        return Text.empty()
                .append(playerName)
                .append(Text.literal(" acaba de capturar ").formatted(Formatting.DARK_AQUA))
                .append(pokemonName)
                .append(Text.literal(" ").formatted(Formatting.DARK_AQUA))
                .append(mythical)
                .append(Text.literal("!").formatted(Formatting.DARK_AQUA));
    }

    private static MutableText legendaryDefeatMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.AQUA))
                .append(Text.literal(" derrotou o lendário ").formatted(Formatting.GRAY))
                .append(pokemonName(pokemon, Formatting.GOLD))
                .append(Text.literal("!").formatted(Formatting.GRAY));
    }

    private static MutableText mythicalDefeatMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.AQUA))
                .append(Text.literal(" derrotou o mítico ").formatted(Formatting.GRAY))
                .append(pokemonName(pokemon, Formatting.LIGHT_PURPLE))
                .append(Text.literal("!").formatted(Formatting.GRAY));
    }

    private static MutableText shinyDefeatMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" derrotou um ").formatted(Formatting.GRAY))
                .append(pokemonName(pokemon, Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.GRAY))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal("!").formatted(Formatting.GRAY));
    }

    private static MutableText shinyLegendaryDefeatMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" derrotou o lendário ").formatted(Formatting.GRAY))
                .append(pokemonName(pokemon, Formatting.GOLD))
                .append(Text.literal(" ").formatted(Formatting.GRAY))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal("!").formatted(Formatting.GRAY));
    }

    private static MutableText shinyMythicalDefeatMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" derrotou o mítico ").formatted(Formatting.GRAY))
                .append(pokemonName(pokemon, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" ").formatted(Formatting.GRAY))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal("!").formatted(Formatting.GRAY));
    }

    private static MutableText shinyLegendaryCaptureMessage(ServerPlayerEntity player, Object pokemon) {
        MutableText playerName = playerName(player, Formatting.LIGHT_PURPLE);
        MutableText pokemonName = pokemonName(pokemon, Formatting.GOLD);
        MutableText shiny = Text.literal("SHINY").formatted(Formatting.GOLD);
        MutableText legendary = Text.literal("LENDÁRIO").formatted(Formatting.RED);

        return Text.empty()
                .append(playerName)
                .append(Text.literal(" acabou de capturar ").formatted(Formatting.DARK_RED))
                .append(pokemonName)
                .append(Text.literal(" ").formatted(Formatting.DARK_RED))
                .append(shiny)
                .append(Text.literal(" ").formatted(Formatting.DARK_RED))
                .append(legendary)
                .append(Text.literal("!").formatted(Formatting.DARK_RED));
    }

    private static MutableText shinyMythicalCaptureMessage(ServerPlayerEntity player, Object pokemon) {
        MutableText playerName = playerName(player, Formatting.LIGHT_PURPLE);
        MutableText pokemonName = pokemonName(pokemon, Formatting.GOLD);
        MutableText shiny = Text.literal("SHINY").formatted(Formatting.GOLD);
        MutableText mythical = Text.literal("MÍTICO").formatted(Formatting.AQUA);

        return Text.empty()
                .append(playerName)
                .append(Text.literal(" acabou de capturar ").formatted(Formatting.DARK_PURPLE))
                .append(pokemonName)
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(shiny)
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(mythical)
                .append(Text.literal("!").formatted(Formatting.DARK_PURPLE));
    }

    private static MutableText shinyHatchMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" acabou de chocar um ").formatted(Formatting.DARK_PURPLE))
                .append(pokemonName(pokemon, Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal(" de um ovo!").formatted(Formatting.DARK_PURPLE));
    }

    private static MutableText shinyFossilMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" acabou de reviver um ").formatted(Formatting.DARK_PURPLE))
                .append(pokemonName(pokemon, Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal(" de um fóssil!").formatted(Formatting.DARK_PURPLE));
    }

    private static MutableText shinyIncubatorMessage(ServerPlayerEntity player, Object pokemon) {
        return Text.empty()
                .append(playerName(player, Formatting.LIGHT_PURPLE))
                .append(Text.literal(" acabou de obter um ").formatted(Formatting.DARK_PURPLE))
                .append(pokemonName(pokemon, Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.DARK_PURPLE))
                .append(Text.literal("SHINY").formatted(Formatting.GOLD))
                .append(Text.literal(" na Incubadora!").formatted(Formatting.DARK_PURPLE));
    }

    private static MutableText playerName(ServerPlayerEntity player, Formatting formatting) {
        return Text.literal(player.getGameProfile().getName()).formatted(formatting);
    }

    private static MutableText pokemonName(Object pokemon, Formatting formatting) {
        return Text.literal(resolveSpeciesName(pokemon).toUpperCase(Locale.ROOT)).formatted(formatting);
    }

    private static boolean isShiny(Object pokemon) {
        Object value = invokeNoArgsQuietly(pokemon, "getShiny");
        if (value instanceof Boolean bool) {
            return bool;
        }
        value = invokeNoArgsQuietly(pokemon, "isShiny");
        return value instanceof Boolean bool && bool;
    }

    private static boolean isLegendary(Object pokemon) {
        Object species = invokeNoArgsQuietly(pokemon, "getSpecies");
        if (hasLabel(species, "legendary")) {
            return true;
        }

        // Form labels are checked as a fallback for addon/custom forms that classify rarity there.
        Object form = invokeNoArgsQuietly(pokemon, "getForm");
        return hasLabel(form, "legendary");
    }

    private static boolean isMythical(Object pokemon) {
        Object species = invokeNoArgsQuietly(pokemon, "getSpecies");
        if (hasLabel(species, "mythical")) {
            return true;
        }

        // Keep the same form-label fallback used for Legendary classification.
        Object form = invokeNoArgsQuietly(pokemon, "getForm");
        return hasLabel(form, "mythical");
    }

    private static boolean hasLabel(Object target, String expected) {
        Object labels = invokeNoArgsQuietly(target, "getLabels");
        if (!(labels instanceof Collection<?> collection)) {
            return false;
        }
        for (Object label : collection) {
            if (label != null && expected.equalsIgnoreCase(label.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private static String resolveSpeciesName(Object pokemon) {
        Object species = invokeNoArgsQuietly(pokemon, "getSpecies");
        Object name = invokeNoArgsQuietly(species, "getName");
        if (name != null && !name.toString().isBlank()) {
            return name.toString();
        }

        Object identifier = invokeNoArgsQuietly(species, "getResourceIdentifier");
        Object path = invokeNoArgsQuietly(identifier, "getPath");
        if (path != null && !path.toString().isBlank()) {
            return humanize(path.toString());
        }

        return "Pokémon";
    }

    private static String humanize(String raw) {
        String cleaned = raw.trim().replace('-', '_').replace('.', '_');
        String[] words = cleaned.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? "Pokémon" : result.toString();
    }

    private static ServerPlayerEntity extractPlayer(Object event) {
        Object player = invokeNoArgsQuietly(event, "getPlayer");
        return player instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
    }

    private static ServerPlayerEntity extractPlayerFromGainedEvent(Object event) {
        ServerPlayerEntity direct = extractPlayer(event);
        if (direct != null) {
            return direct;
        }

        Object playerId = invokeNoArgsQuietly(event, "getPlayerId");
        if (!(playerId instanceof UUID uuid) || activeServer == null) {
            return null;
        }
        return activeServer.getPlayerManager().getPlayer(uuid);
    }

    private static boolean isNearCompletedCobbleSafariIncubator(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos origin = player.getBlockPos();

        for (int dx = -INCUBATOR_SCAN_RADIUS_XZ; dx <= INCUBATOR_SCAN_RADIUS_XZ; dx++) {
            for (int dy = -INCUBATOR_SCAN_RADIUS_Y; dy <= INCUBATOR_SCAN_RADIUS_Y; dy++) {
                for (int dz = -INCUBATOR_SCAN_RADIUS_XZ; dz <= INCUBATOR_SCAN_RADIUS_XZ; dz++) {
                    BlockPos pos = new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    Identifier blockId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
                    if (blockId == null || !COBBLESAFARI_INCUBATOR_ID.equals(blockId.toString())) {
                        continue;
                    }

                    Object blockEntity = world.getBlockEntity(pos);
                    Object done = invokeNoArgsQuietly(blockEntity, "isDone");
                    if (done instanceof Boolean bool && bool) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Object extractPokemon(Object event) {
        return invokeNoArgsQuietly(event, "getPokemon");
    }

    private static Object invokeNoArgsQuietly(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void subscribe(String fieldName, Consumer<Object> consumer) throws Exception {
        Class<?> eventsClass = Class.forName(COBBLEMON_EVENTS_CLASS);
        Object observable = eventsClass.getField(fieldName).get(null);

        Method subscribe = Arrays.stream(observable.getClass().getMethods())
                .filter(method -> method.getName().equals("subscribe"))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> Consumer.class.isAssignableFrom(method.getParameterTypes()[0]))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(
                        observable.getClass().getName() + ".subscribe(Consumer) for " + fieldName
                ));

        subscribe.invoke(observable, consumer);
    }

    private static void disableLegacyCoreShinyNotifier() {
        if (!ServerFixesConfig.takeOverCobbleKantoShinyNotifications
                || !FabricLoader.getInstance().isModLoaded("cobblekanto")) {
            return;
        }

        try {
            Class<?> configClass = Class.forName(CORE_SHINY_CONFIG_CLASS);
            Method ensureLoaded = configClass.getMethod("ensureLoaded");
            ensureLoaded.invoke(null);

            Field enabled = configClass.getDeclaredField("enabled");
            enabled.setAccessible(true);
            enabled.setBoolean(null, false);

            if (!coreNotifierTakeoverLogged) {
                coreNotifierTakeoverLogged = true;
                CobbleKantoServerFixes.LOGGER.info(
                        "ServerFixes took over CobbleKanto's legacy shiny capture notifier; duplicate announcements are suppressed."
                );
            }
        } catch (ClassNotFoundException ignored) {
            // Core is optional on servers that only need ServerFixes.
        } catch (Throwable throwable) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not suppress CobbleKanto's legacy shiny capture notifier; duplicate shiny-capture messages may occur.",
                    throwable
            );
        }
    }
}
