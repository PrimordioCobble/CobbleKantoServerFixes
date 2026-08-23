package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Removes Kanto species from Cobblemon's world spawn pool once when the pool is loaded.
 *
 * <p>This deliberately avoids Cobblemon spawn-rule MoLang expressions. Those expressions are
 * evaluated in the hot spawn-selection path and can become extremely expensive when they repeatedly
 * materialize {@code spawn_detail.pokemon}. Filtering the mutable pool once and recalculating its
 * indexes has no recurring per-spawn cost.</p>
 *
 * <p>The integration is reflection based so CobbleKantoServerFixes remains optional with respect to
 * Cobblemon at compile time and continues to load safely on backends where Cobblemon is absent.</p>
 */
public final class KantoNaturalSpawnFilter {
    private static final String WORLD_POOL_NAME = "world";
    private static final String POKEMON_SPAWN_DETAIL_CLASS =
            "com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail";
    private static final String POKEMON_HERD_SPAWN_DETAIL_CLASS =
            "com.cobblemon.mod.common.api.spawning.detail.PokemonHerdSpawnDetail";

    private static final Set<String> KANTO_SPECIES = Set.of(
            "bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon", "charizard",
            "squirtle", "wartortle", "blastoise", "caterpie", "metapod", "butterfree",
            "weedle", "kakuna", "beedrill", "pidgey", "pidgeotto", "pidgeot",
            "rattata", "raticate", "spearow", "fearow", "ekans", "arbok",
            "pikachu", "raichu", "sandshrew", "sandslash", "nidoranf", "nidorina",
            "nidoqueen", "nidoranm", "nidorino", "nidoking", "clefairy", "clefable",
            "vulpix", "ninetales", "jigglypuff", "wigglytuff", "zubat", "golbat",
            "oddish", "gloom", "vileplume", "paras", "parasect", "venonat",
            "venomoth", "diglett", "dugtrio", "meowth", "persian", "psyduck",
            "golduck", "mankey", "primeape", "growlithe", "arcanine", "poliwag",
            "poliwhirl", "poliwrath", "abra", "kadabra", "alakazam", "machop",
            "machoke", "machamp", "bellsprout", "weepinbell", "victreebel", "tentacool",
            "tentacruel", "geodude", "graveler", "golem", "ponyta", "rapidash",
            "slowpoke", "slowbro", "magnemite", "magneton", "farfetchd", "doduo",
            "dodrio", "seel", "dewgong", "grimer", "muk", "shellder",
            "cloyster", "gastly", "haunter", "gengar", "onix", "drowzee",
            "hypno", "krabby", "kingler", "voltorb", "electrode", "exeggcute",
            "exeggutor", "cubone", "marowak", "hitmonlee", "hitmonchan", "lickitung",
            "koffing", "weezing", "rhyhorn", "rhydon", "chansey", "tangela",
            "kangaskhan", "horsea", "seadra", "goldeen", "seaking", "staryu",
            "starmie", "mrmime", "scyther", "jynx", "electabuzz", "magmar",
            "pinsir", "tauros", "magikarp", "gyarados", "lapras", "ditto",
            "eevee", "vaporeon", "jolteon", "flareon", "porygon", "omanyte",
            "omastar", "kabuto", "kabutops", "aerodactyl", "snorlax", "articuno",
            "zapdos", "moltres", "dratini", "dragonair", "dragonite", "mewtwo", "mew"
    );

    private KantoNaturalSpawnFilter() {
    }

    /** Called by the SpawnPool mixin after Cobblemon fills the pool and before it optimizes the pool. */
    public static void apply(Object spawnPool) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.blockKantoNaturalSpawns || spawnPool == null) {
            return;
        }

        try {
            String poolName = String.valueOf(invokeNoArgs(spawnPool, "getName"));
            if (!WORLD_POOL_NAME.equals(poolName)) {
                return;
            }

            Object detailsValue = invokeNoArgs(spawnPool, "getDetails");
            if (!(detailsValue instanceof List<?> details)) {
                CobbleKantoServerFixes.LOGGER.error(
                        "Could not filter Kanto natural spawns: Cobblemon world SpawnPool details are not a List."
                );
                return;
            }

            FilterResult result = filterDetails(details);

            if (ServerFixesConfig.logKantoNaturalSpawnFilter) {
                CobbleKantoServerFixes.LOGGER.info(
                        "Kanto natural spawn filter applied to Cobblemon world pool: "
                                + "removedRegularDetails={}, removedHerdMembers={}, removedEmptyHerdDetails={}, "
                                + "remainingDetails={}, allowedExceptions={}",
                        result.removedRegularDetails,
                        result.removedHerdMembers,
                        result.removedEmptyHerdDetails,
                        details.size(),
                        ServerFixesConfig.allowedKantoNaturalSpawnSpecies
                );
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to apply the Kanto natural spawn filter. The server will keep Cobblemon's unfiltered spawn pool.",
                    unwrap(exception)
            );
        }
    }

    private static FilterResult filterDetails(List<?> details) throws ReflectiveOperationException {
        int removedRegularDetails = 0;
        int removedHerdMembers = 0;
        int removedEmptyHerdDetails = 0;

        Iterator<?> detailIterator = details.iterator();
        while (detailIterator.hasNext()) {
            Object detail = detailIterator.next();
            if (detail == null) {
                continue;
            }

            if (isClassOrSubclass(detail, POKEMON_SPAWN_DETAIL_CLASS)) {
                Object pokemonProperties = invokeNoArgs(detail, "getPokemon");
                if (isBlockedPokemonProperties(pokemonProperties)) {
                    detailIterator.remove();
                    removedRegularDetails++;
                }
                continue;
            }

            if (!isClassOrSubclass(detail, POKEMON_HERD_SPAWN_DETAIL_CLASS)) {
                continue;
            }

            Object herdValue = invokeNoArgs(detail, "getHerdablePokemon");
            if (!(herdValue instanceof List<?> herdablePokemon)) {
                continue;
            }

            Iterator<?> herdIterator = herdablePokemon.iterator();
            while (herdIterator.hasNext()) {
                Object herdable = herdIterator.next();
                if (herdable == null) {
                    continue;
                }
                Object pokemonProperties = invokeNoArgs(herdable, "getPokemon");
                if (isBlockedPokemonProperties(pokemonProperties)) {
                    herdIterator.remove();
                    removedHerdMembers++;
                }
            }

            if (herdablePokemon.isEmpty()) {
                detailIterator.remove();
                removedEmptyHerdDetails++;
            }
        }

        return new FilterResult(removedRegularDetails, removedHerdMembers, removedEmptyHerdDetails);
    }

    private static boolean isBlockedPokemonProperties(Object pokemonProperties) throws ReflectiveOperationException {
        if (pokemonProperties == null) {
            return false;
        }

        Object speciesValue = invokeNoArgs(pokemonProperties, "getSpecies");
        if (!(speciesValue instanceof String species)) {
            return false;
        }

        String normalizedSpecies = normalizeSpecies(species);
        return KANTO_SPECIES.contains(normalizedSpecies)
                && !ServerFixesConfig.allowedKantoNaturalSpawnSpecies.contains(normalizedSpecies);
    }

    private static String normalizeSpecies(String species) {
        String normalized = species.trim().toLowerCase(Locale.ROOT);
        int namespaceSeparator = normalized.lastIndexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator + 1 < normalized.length()) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        return normalized;
    }

    private static boolean isClassOrSubclass(Object value, String expectedClassName) {
        Class<?> type = value.getClass();
        while (type != null) {
            if (expectedClassName.equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Throwable unwrap(Exception exception) {
        if (exception instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return exception;
    }

    private record FilterResult(
            int removedRegularDetails,
            int removedHerdMembers,
            int removedEmptyHerdDetails
    ) {
    }
}
