package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Server-side guard that forces Raid Dens boss entities to remain non-shiny. */
public final class RaidBossShinyGuardBridge {
    private RaidBossShinyGuardBridge() {
    }

    public static void forceNormalBossEntity(Object pokemonEntity) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.preventRaidBossShiny || pokemonEntity == null) {
            return;
        }

        try {
            Object pokemon = invokeNoArgs(pokemonEntity, "getPokemon", "pokemon");
            if (pokemon == null) {
                return;
            }

            Method setShiny = pokemon.getClass().getMethod("setShiny", boolean.class);
            setShiny.setAccessible(true);
            setShiny.invoke(pokemon, false);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not force a Cobblemon Raid Dens boss Pokémon to non-shiny.",
                    unwrap(exception)
            );
        }
    }

    private static Object invokeNoArgs(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return throwable;
    }
}
