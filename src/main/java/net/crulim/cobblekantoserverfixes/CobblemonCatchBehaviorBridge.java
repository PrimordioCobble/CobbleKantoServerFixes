package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Server-side compatibility layer for Cobblemon 1.7.3 capture behavior.
 *
 * <p>The mixin-facing methods remove the player Pokédex dependency from critical captures and
 * normalize Cobblemon's repeated-catch presentation back to an ordinary four-shake success.
 * External catch-rate listeners can also be neutralized, but that option is intentionally disabled
 * by default so positive CobbleCuisine and CobbleSafari bonuses remain untouched.</p>
 */
public final class CobblemonCatchBehaviorBridge {
    private static final String CAPTURE_CONTEXT_CLASS =
            "com.cobblemon.mod.common.api.pokeball.catching.CaptureContext";
    private static final String COBBLEMON_EVENTS_CLASS =
            "com.cobblemon.mod.common.api.events.CobblemonEvents";
    private static final String PRIORITY_CLASS =
            "com.cobblemon.mod.common.api.Priority";

    private static volatile CaptureContextBindings captureContextBindings;
    private static volatile boolean captureContextBindingFailureLogged;
    private static volatile boolean catchRateListenerRegistered;
    private static volatile boolean criticalCaptureMixinObserved;
    private static volatile boolean pokedexPresentationMixinObserved;

    private CobblemonCatchBehaviorBridge() {
    }

    public static void register() {
        if (!isBridgeEnabled()) {
            CobbleKantoServerFixes.LOGGER.info("Cobblemon catch behavior bridge is disabled by config.");
            return;
        }

        if (ServerFixesConfig.neutralizeExternalCatchRateModifiers) {
            registerCatchRateNeutralizer();
        }

        CobbleKantoServerFixes.LOGGER.info(
                "Cobblemon catch behavior bridge enabled. disableCriticalCaptureRolls={}, "
                        + "normalizePokedexCatchPresentation={}, neutralizeExternalCatchRateModifiers={}",
                ServerFixesConfig.disableCobblemonCriticalCaptureRolls,
                ServerFixesConfig.normalizeCobblemonPokedexCatchPresentation,
                ServerFixesConfig.neutralizeExternalCatchRateModifiers
        );
    }

    public static boolean shouldDisableCriticalCaptureRolls() {
        boolean disabled = isBridgeEnabled() && ServerFixesConfig.disableCobblemonCriticalCaptureRolls;
        if (disabled && !criticalCaptureMixinObserved) {
            criticalCaptureMixinObserved = true;
            CobbleKantoServerFixes.LOGGER.info(
                    "Cobblemon critical-capture mixin hook confirmed active."
            );
        }
        return disabled;
    }

    /**
     * Converts only successful critical contexts produced by Cobblemon's Pokédex-status influencer
     * into a normal successful context. Failed contexts are never changed.
     */
    public static Object normalizePokedexCatchPresentation(Object captureContext) {
        if (!isBridgeEnabled()
                || !ServerFixesConfig.normalizeCobblemonPokedexCatchPresentation
                || captureContext == null) {
            return captureContext;
        }

        if (!pokedexPresentationMixinObserved) {
            pokedexPresentationMixinObserved = true;
            CobbleKantoServerFixes.LOGGER.info(
                    "Cobblemon Pokédex catch-presentation mixin hook confirmed active."
            );
        }

        try {
            CaptureContextBindings bindings = getCaptureContextBindings(captureContext.getClass());
            boolean successful = (boolean) bindings.isSuccessfulCapture.invoke(captureContext);
            boolean critical = (boolean) bindings.isCriticalCapture.invoke(captureContext);
            if (!successful || !critical) {
                return captureContext;
            }

            Object normalized = bindings.constructor.newInstance(4, true, false);
            if (ServerFixesConfig.logCobblemonCatchBehaviorChanges) {
                CobbleKantoServerFixes.LOGGER.info(
                        "Normalized a Pokédex-forced critical capture presentation to a normal four-shake success."
                );
            }
            return normalized;
        } catch (Throwable throwable) {
            logCaptureContextBindingFailure(throwable);
            return captureContext;
        }
    }

    private static boolean isBridgeEnabled() {
        return ServerFixesConfig.enabled && ServerFixesConfig.cobblemonCatchBehaviorBridgeEnabled;
    }

    private static CaptureContextBindings getCaptureContextBindings(Class<?> runtimeClass) throws Exception {
        CaptureContextBindings cached = captureContextBindings;
        if (cached != null && cached.captureContextClass == runtimeClass) {
            return cached;
        }

        if (!CAPTURE_CONTEXT_CLASS.equals(runtimeClass.getName())) {
            throw new IllegalArgumentException("Unexpected capture context class: " + runtimeClass.getName());
        }

        Constructor<?> constructor = runtimeClass.getConstructor(int.class, boolean.class, boolean.class);
        Method isSuccessfulCapture = runtimeClass.getMethod("isSuccessfulCapture");
        Method isCriticalCapture = runtimeClass.getMethod("isCriticalCapture");
        CaptureContextBindings created = new CaptureContextBindings(
                runtimeClass,
                constructor,
                isSuccessfulCapture,
                isCriticalCapture
        );
        captureContextBindings = created;
        return created;
    }

    private static void logCaptureContextBindingFailure(Throwable throwable) {
        if (captureContextBindingFailureLogged) {
            return;
        }
        captureContextBindingFailureLogged = true;
        CobbleKantoServerFixes.LOGGER.error(
                "Failed to normalize Cobblemon capture presentation. The server will keep Cobblemon's original result.",
                throwable
        );
    }

    private static void registerCatchRateNeutralizer() {
        if (catchRateListenerRegistered) {
            return;
        }

        try {
            Class<?> eventsClass = Class.forName(COBBLEMON_EVENTS_CLASS);
            Class<?> priorityClass = Class.forName(PRIORITY_CLASS);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object lowest = Enum.valueOf((Class<? extends Enum>) priorityClass.asSubclass(Enum.class), "LOWEST");
            Object observable = eventsClass.getField("POKEMON_CATCH_RATE").get(null);
            subscribe(observable, lowest, CobblemonCatchBehaviorBridge::normalizeCatchRateEvent);
            catchRateListenerRegistered = true;
            CobbleKantoServerFixes.LOGGER.warn(
                    "External Cobblemon catch-rate modifiers are being neutralized to each form's base catch rate."
            );
        } catch (ClassNotFoundException exception) {
            CobbleKantoServerFixes.LOGGER.info(
                    "Cobblemon is not available; catch-rate neutralizer was not registered."
            );
        } catch (Throwable throwable) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to register the optional Cobblemon catch-rate neutralizer.",
                    throwable
            );
        }
    }

    private static void subscribe(Object observable, Object priority, Consumer<Object> consumer) throws Exception {
        Method subscribe = Arrays.stream(observable.getClass().getMethods())
                .filter(method -> method.getName().equals("subscribe"))
                .filter(method -> method.getParameterCount() == 2)
                .filter(method -> Consumer.class.isAssignableFrom(method.getParameterTypes()[1]))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("EventObservable.subscribe(Priority, Consumer)"));
        subscribe.invoke(observable, priority, consumer);
    }

    private static void normalizeCatchRateEvent(Object event) {
        if (!isBridgeEnabled() || !ServerFixesConfig.neutralizeExternalCatchRateModifiers) {
            return;
        }

        try {
            float before = asFloat(invokeNoArgs(event, "getCatchRate"));
            Object pokemonEntity = invokeNoArgs(event, "getPokemonEntity");
            Object pokemon = invokeNoArgs(pokemonEntity, "getPokemon");
            Object form = invokeNoArgs(pokemon, "getForm");
            float baseCatchRate = asFloat(invokeNoArgs(form, "getCatchRate"));
            invokeSingleArgument(event, "setCatchRate", baseCatchRate);

            if (ServerFixesConfig.logCobblemonCatchBehaviorChanges
                    && Float.compare(before, baseCatchRate) != 0) {
                CobbleKantoServerFixes.LOGGER.info(
                        "Neutralized external catch-rate modification: {} -> {}.",
                        before,
                        baseCatchRate
                );
            }
        } catch (Throwable throwable) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to neutralize a Cobblemon catch-rate event; the original event value was kept.",
                    throwable
            );
        }
    }

    private static Object invokeNoArgs(Object target, String methodName) throws Exception {
        if (target == null) {
            throw new IllegalStateException("Cannot invoke " + methodName + " on null");
        }
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static void invokeSingleArgument(Object target, String methodName, Object argument) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType == float.class && argument instanceof Float) {
                method.invoke(target, argument);
                return;
            }
            if (argument != null && parameterType.isAssignableFrom(argument.getClass())) {
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + methodName + "(one argument)");
    }

    private static float asFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        throw new IllegalArgumentException("Expected numeric value, got " + value);
    }

    private record CaptureContextBindings(
            Class<?> captureContextClass,
            Constructor<?> constructor,
            Method isSuccessfulCapture,
            Method isCriticalCapture
    ) {
    }
}
