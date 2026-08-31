package net.crulim.cobblekantoserverfixes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only runtime probe for the historical Gen 3 tournament engine.
 *
 * <p>This bridge intentionally does not repair or mutate Showdown. It runs from
 * BATTLE_STARTED_PRE, after ServerFixes has constructed the PokemonBattle with mod=gen3/gen=3
 * but before Cobblemon calls startShowdown(). The goal is to prove which species/type data the
 * live GraalJS simulator will resolve.</p>
 *
 * <p>The PRE-battle diagnostic remains fail-open and read-only. The explicit activation guard is
 * deliberately fail-closed: /cktournament gen3 on is refused when the live runtime cannot prove
 * the required historical mechanics.</p>
 */
public final class TournamentGen3ShowdownDiagnosticBridge {
    private static final String SHOWDOWN_SERVICE_CLASS = "com.cobblemon.mod.common.battles.runner.ShowdownService";
    private static final Gson GSON = new Gson();
    private static final AtomicLong RUNS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();

    private static volatile String state = "WAITING";
    private static volatile String last = "aguardando primeiro PvP Gen 3";
    private static volatile Object diagnosticFunction;
    private static volatile Method valueExecute;
    private static volatile Method valueAsString;

    private static final String JS_DIAGNOSTIC_SOURCE = """
            (() => {
              if (globalThis.__cksGen3DexDiagnosticV3) return;

              const {Dex, toID} = require('./sim/dex');

              function arrayCopy(value) {
                return Array.isArray(value) ? [...value] : [];
              }

              function damageTaken(data, attackingType) {
                if (!data || !data.damageTaken) return null;
                const value = data.damageTaken[attackingType];
                return value === undefined ? null : value;
              }

              function safeCall(callback) {
                try {
                  return callback();
                } catch (error) {
                  return {error: String(error && (error.stack || error.message) || error)};
                }
              }

              globalThis.__cksGen3DexDiagnosticV3 = function(extraSpeciesJson) {
                const dex3 = Dex.mod('gen3');
                let registry = null;
                let registryError = '';
                try {
                  const {Cobblemon} = require('./sim/cobblemon/cobblemon');
                  registry = Cobblemon && Cobblemon.registries ? Cobblemon.registries.species : null;
                } catch (error) {
                  registryError = String(error && (error.stack || error.message) || error);
                }

                let extraSpecies = [];
                try {
                  const parsed = JSON.parse(String(extraSpeciesJson || '[]'));
                  if (Array.isArray(parsed)) extraSpecies = parsed;
                } catch (_) {
                }

                const sentinels = [
                  'azumarill', 'azumarilllegacy',
                  'togetic', 'togeticlegacy',
                  'gardevoir', 'gardevoirlegacy',
                  'skarmory', 'metagross'
                ];
                const ids = [...new Set([...sentinels, ...extraSpecies].map(toID).filter(Boolean))];

                function speciesSnapshot(rawId) {
                  const id = toID(rawId || '');
                  const resolved = safeCall(() => dex3.species.get(id));
                  const historical = dex3.data && dex3.data.Pokedex ? dex3.data.Pokedex[id] : null;
                  const registered = registry ? safeCall(() => registry.get(id)) : null;

                  const resolvedError = resolved && resolved.error ? resolved.error : '';
                  const registryGetError = registered && registered.error ? registered.error : '';
                  const resolvedSpecies = resolvedError ? null : resolved;
                  const registeredSpecies = registryGetError ? null : registered;

                  return {
                    id,
                    resolvedExists: !!(resolvedSpecies && resolvedSpecies.exists),
                    resolvedName: resolvedSpecies && resolvedSpecies.name || '',
                    resolvedTypes: arrayCopy(resolvedSpecies && resolvedSpecies.types),
                    historicalTypes: arrayCopy(historical && historical.types),
                    registryExists: !!registeredSpecies,
                    registryName: registeredSpecies && registeredSpecies.name || '',
                    registryTypes: arrayCopy(registeredSpecies && registeredSpecies.types),
                    dragonEffectiveness: resolvedSpecies ? safeCall(() => dex3.getEffectiveness('Dragon', resolvedSpecies)) : null,
                    dragonImmunity: resolvedSpecies ? safeCall(() => dex3.getImmunity('Dragon', resolvedSpecies)) : null,
                    darkEffectiveness: resolvedSpecies ? safeCall(() => dex3.getEffectiveness('Dark', resolvedSpecies)) : null,
                    ghostEffectiveness: resolvedSpecies ? safeCall(() => dex3.getEffectiveness('Ghost', resolvedSpecies)) : null,
                    resolvedError,
                    registryGetError
                  };
                }

                const effectiveSteel = safeCall(() => dex3.types.get('Steel'));
                const rawSteel = dex3.data && dex3.data.TypeChart
                  ? dex3.data.TypeChart[toID('Steel')]
                  : null;

                const shadowBall = safeCall(() => dex3.moves.get('shadowball'));
                const bite = safeCall(() => dex3.moves.get('bite'));
                const rawShadowBall = dex3.data && dex3.data.Moves ? dex3.data.Moves.shadowball : null;
                const rawBite = dex3.data && dex3.data.Moves ? dex3.data.Moves.bite : null;
                const resolvedHydroPump = safeCall(() => dex3.moves.get('hydropump'));
                const resolvedHurricane = safeCall(() => dex3.moves.get('hurricane'));
                const resolvedChoiceBand = safeCall(() => dex3.items.get('choiceband'));
                const resolvedChoiceScarf = safeCall(() => dex3.items.get('choicescarf'));
                const natures = dex3.data && dex3.data.Natures ? dex3.data.Natures : {};

                return JSON.stringify({
                  dex: {
                    gen: dex3.gen,
                    mod: dex3.currentMod || '',
                    registryAvailable: !!registry,
                    registryError
                  },
                  species: ids.map(speciesSnapshot),
                  steel: {
                    effectiveDarkDamageTaken: effectiveSteel && !effectiveSteel.error ? damageTaken(effectiveSteel, 'Dark') : null,
                    effectiveGhostDamageTaken: effectiveSteel && !effectiveSteel.error ? damageTaken(effectiveSteel, 'Ghost') : null,
                    rawDarkDamageTaken: damageTaken(rawSteel, 'Dark'),
                    rawGhostDamageTaken: damageTaken(rawSteel, 'Ghost'),
                    darkEffectiveness: safeCall(() => dex3.getEffectiveness('Dark', 'Steel')),
                    ghostEffectiveness: safeCall(() => dex3.getEffectiveness('Ghost', 'Steel')),
                    darkImmunity: safeCall(() => dex3.getImmunity('Dark', 'Steel')),
                    ghostImmunity: safeCall(() => dex3.getImmunity('Ghost', 'Steel'))
                  },
                  split: {
                    shadowBallResolvedCategory: shadowBall && !shadowBall.error ? shadowBall.category || '' : '',
                    shadowBallHistoricalCategory: rawShadowBall && rawShadowBall.category || '',
                    biteResolvedCategory: bite && !bite.error ? bite.category || '' : '',
                    biteHistoricalCategory: rawBite && rawBite.category || ''
                  },
                  policy: {
                    moveGenerationSource: 'dex3.moves.get',
                    itemGenerationSource: 'dex3.items.get',
                    hydroPumpGen: resolvedHydroPump && !resolvedHydroPump.error && Number.isFinite(resolvedHydroPump.gen) ? resolvedHydroPump.gen : 0,
                    hydroPumpNum: resolvedHydroPump && !resolvedHydroPump.error && Number.isFinite(resolvedHydroPump.num) ? resolvedHydroPump.num : 0,
                    hurricaneGen: resolvedHurricane && !resolvedHurricane.error && Number.isFinite(resolvedHurricane.gen) ? resolvedHurricane.gen : 0,
                    hurricaneNum: resolvedHurricane && !resolvedHurricane.error && Number.isFinite(resolvedHurricane.num) ? resolvedHurricane.num : 0,
                    hurricaneNonstandard: resolvedHurricane && !resolvedHurricane.error ? resolvedHurricane.isNonstandard || '' : '',
                    choiceBandGen: resolvedChoiceBand && !resolvedChoiceBand.error && Number.isFinite(resolvedChoiceBand.gen) ? resolvedChoiceBand.gen : 0,
                    choiceScarfGen: resolvedChoiceScarf && !resolvedChoiceScarf.error && Number.isFinite(resolvedChoiceScarf.gen) ? resolvedChoiceScarf.gen : 0,
                    adamantNaturePresent: !!natures.adamant,
                    modestNaturePresent: !!natures.modest
                  }
                });
              };
            })();
            """;

    private TournamentGen3ShowdownDiagnosticBridge() {
    }

    /**
     * Fail-closed activation guard for the historical battle engine. This executes against the
     * exact live GraalJS Showdown context. The engine is allowed to turn ON only when the runtime
     * proves the Gen 3 type chart, type-based physical/special split, generation metadata used by
     * the FUTURE-only legality locks, and the datapack's Azumarill Legacy typing.
     */
    public static synchronized EngineReadiness verifyHistoricalEngineReadiness() {
        try {
            JsonObject root = JsonParser.parseString(executeDiagnostic("[]")).getAsJsonObject();
            List<String> problems = new ArrayList<>();

            JsonObject dex = object(root, "dex");
            if (integer(dex, "gen") != 3) {
                problems.add("Dex.mod(gen3) retornou gen=" + integer(dex, "gen"));
            }
            if (!bool(dex, "registryAvailable")) {
                problems.add("Cobblemon.registries.species indisponível no GraalJS");
            }

            JsonObject steel = object(root, "steel");
            if (integer(steel, "effectiveDarkDamageTaken") != 2
                    || integer(steel, "effectiveGhostDamageTaken") != 2
                    || integer(steel, "rawDarkDamageTaken") != 2
                    || integer(steel, "rawGhostDamageTaken") != 2
                    || integer(steel, "darkEffectiveness") != -1
                    || integer(steel, "ghostEffectiveness") != -1
                    || !bool(steel, "darkImmunity")
                    || !bool(steel, "ghostImmunity")) {
                problems.add("type chart Steel não é histórico Gen 3: " + steel);
            }

            JsonObject split = object(root, "split");
            if (!"Physical".equalsIgnoreCase(string(split, "shadowBallResolvedCategory"))
                    || !"Special".equalsIgnoreCase(string(split, "biteResolvedCategory"))) {
                problems.add("physical/special split não é Gen 3: " + split);
            }

            JsonObject policy = object(root, "policy");
            if (!"dex3.moves.get".equals(string(policy, "moveGenerationSource"))
                    || integer(policy, "hydroPumpGen") != 1
                    || integer(policy, "hydroPumpNum") != 56
                    || integer(policy, "hurricaneGen") <= 3
                    || integer(policy, "hurricaneNum") != 542
                    || integer(policy, "choiceBandGen") <= 0
                    || integer(policy, "choiceBandGen") > 3
                    || integer(policy, "choiceScarfGen") <= 3
                    || !bool(policy, "adamantNaturePresent")
                    || !bool(policy, "modestNaturePresent")) {
                problems.add("metadados FUTURE-only/natures inesperados: " + policy);
            }

            JsonObject azumarillLegacy = species(root, "azumarilllegacy");
            List<String> legacyTypes = array(azumarillLegacy, "resolvedTypes");
            if (!bool(azumarillLegacy, "resolvedExists")
                    || !bool(azumarillLegacy, "registryExists")
                    || !legacyTypes.equals(List.of("Water"))
                    || !array(azumarillLegacy, "registryTypes").equals(List.of("Water"))
                    || !bool(azumarillLegacy, "dragonImmunity")) {
                problems.add("Azumarill Legacy não resolveu como Water no registry do Showdown: " + azumarillLegacy);
            }

            if (!problems.isEmpty()) {
                throw new IllegalStateException(String.join(" | ", problems));
            }

            state = "GUARD-READY";
            last = "engine histórico validado antes de ligar";
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3-GUARD] READY: gen3 Dex, Steel Dark/Ghost resist, Gen3 split, Azumarill Legacy=Water and FUTURE-only sentinels validated in live GraalJS."
            );
            return EngineReadiness.ok("READY");
        } catch (Throwable throwable) {
            long failures = FAILURES.incrementAndGet();
            state = "GUARD-FAIL:" + throwable.getClass().getSimpleName();
            last = "guard failure #" + failures + ":" + safeMessage(throwable);
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3-GUARD] FAILED: historical engine will remain OFF.",
                    throwable
            );
            return EngineReadiness.fail(safeMessage(throwable));
        }
    }

    public static String statusSummary() {
        return state + ",runs=" + RUNS.get() + ",failures=" + FAILURES.get() + ",last=" + last;
    }

    /** Called from BATTLE_STARTED_PRE. This method never throws into Cobblemon. */
    public static void inspectBeforeShowdown(Object event) {
        if (!TournamentGen3BattleBridge.isEnabled() || event == null) {
            return;
        }

        try {
            Object battle = invokeNoArgs(event, "getBattle");
            if (battle == null || !isPurePlayerPvp(battle)) {
                return;
            }

            Object format = invokeNoArgs(battle, "getFormat");
            String mod = stringValue(invokeNoArgsQuietly(format, "getMod"));
            int gen = intValue(invokeNoArgsQuietly(format, "getGen"), -1);
            if (!"gen3".equalsIgnoreCase(mod) || gen != 3) {
                return;
            }

            List<TeamEntry> team = collectTeam(battle);
            Set<String> sentIds = new LinkedHashSet<>();
            for (TeamEntry entry : team) {
                if (!entry.sentShowdownId().isBlank()) {
                    sentIds.add(entry.sentShowdownId());
                }
            }

            String result = executeDiagnostic(GSON.toJson(sentIds));
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            long run = RUNS.incrementAndGet();
            state = "READY";
            last = "OK#" + run;

            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3-DIAG] PRE #{} format mod={}, gen={} | team={}",
                    run,
                    mod,
                    gen,
                    team
            );
            logDex(json, run);
            logSpecies(json, run);
            logSteel(json, run);
            logSplit(json, run);
        } catch (Throwable throwable) {
            long failures = FAILURES.incrementAndGet();
            state = "FAIL:" + throwable.getClass().getSimpleName();
            last = "FAIL#" + failures + ":" + safeMessage(throwable);
            CobbleKantoServerFixes.LOGGER.warn(
                    "[CKT-GEN3-DIAG] Read-only pre-Showdown diagnostic failed. Battle/engine/locks were NOT changed.",
                    throwable
            );
        }
    }

    private static synchronized String executeDiagnostic(String sentIdsJson) throws Exception {
        ensureJsFunction();
        Object result = valueExecute.invoke(diagnosticFunction, new Object[]{new Object[]{sentIdsJson}});
        if (result == null) {
            throw new IllegalStateException("função JS de diagnóstico retornou null");
        }
        Method asString = result.getClass() == diagnosticFunction.getClass()
                ? valueAsString
                : findCompatibleMethod(result.getClass(), "asString", 0);
        if (asString == null) {
            throw new NoSuchMethodException(result.getClass().getName() + ".asString()");
        }
        asString.setAccessible(true);
        return String.valueOf(asString.invoke(result));
    }

    private static void ensureJsFunction() throws Exception {
        if (diagnosticFunction != null && valueExecute != null && valueAsString != null) {
            return;
        }

        Object service = getShowdownService();
        if (service == null || !service.getClass().getName().endsWith("GraalShowdownService")) {
            throw new IllegalStateException(
                    "ShowdownService ativo não é GraalShowdownService: "
                            + (service == null ? "null" : service.getClass().getName())
            );
        }

        Object context = invokeNoArgs(service, "getContext");
        if (context == null) {
            throw new IllegalStateException("GraalShowdownService.context ainda não está disponível");
        }

        Method eval = findMethod(context.getClass(), "eval", String.class, CharSequence.class);
        if (eval == null) {
            eval = findCompatibleMethod(context.getClass(), "eval", 2);
        }
        if (eval == null) {
            throw new NoSuchMethodException(context.getClass().getName() + ".eval(language, source)");
        }
        eval.setAccessible(true);
        eval.invoke(context, "js", JS_DIAGNOSTIC_SOURCE);

        Object bindings = invoke(context, "getBindings", new Class<?>[]{String.class}, new Object[]{"js"});
        Object function = invoke(
                bindings,
                "getMember",
                new Class<?>[]{String.class},
                new Object[]{"__cksGen3DexDiagnosticV3"}
        );
        if (function == null) {
            throw new IllegalStateException("função JS de diagnóstico não ficou disponível nos bindings");
        }

        Method execute = findCompatibleMethod(function.getClass(), "execute", 1);
        Method asString = findCompatibleMethod(function.getClass(), "asString", 0);
        if (execute == null || asString == null) {
            throw new NoSuchMethodException("Graal Value.execute/asString não encontrados");
        }
        execute.setAccessible(true);
        asString.setAccessible(true);

        diagnosticFunction = function;
        valueExecute = execute;
        valueAsString = asString;
    }

    private static Object getShowdownService() throws Exception {
        Class<?> serviceClass = Class.forName(
                SHOWDOWN_SERVICE_CLASS,
                false,
                TournamentGen3ShowdownDiagnosticBridge.class.getClassLoader()
        );
        Object companion = serviceClass.getField("Companion").get(null);
        return invokeNoArgs(companion, "getService");
    }

    private static List<TeamEntry> collectTeam(Object battle) throws Exception {
        Object actorsValue = invokeNoArgs(battle, "getActors");
        if (!(actorsValue instanceof Iterable<?> actors)) {
            throw new IllegalStateException("PokemonBattle.getActors() não retornou Iterable");
        }

        List<TeamEntry> entries = new ArrayList<>();
        for (Object actor : actors) {
            if (!actorTypeIs(actor, "PLAYER")) {
                continue;
            }
            Object pokemonListValue = invokeNoArgs(actor, "getPokemonList");
            if (!(pokemonListValue instanceof Iterable<?> pokemonList)) {
                throw new IllegalStateException("Player BattleActor.getPokemonList() não retornou Iterable");
            }

            for (Object battlePokemon : pokemonList) {
                Object pokemon = invokeNoArgs(battlePokemon, "getOriginalPokemon");
                if (pokemon == null) {
                    continue;
                }
                Object species = invokeNoArgsQuietly(pokemon, "getSpecies");
                Object form = invokeNoArgsQuietly(pokemon, "getForm");
                entries.add(new TeamEntry(
                        stringValue(invokeNoArgsQuietly(pokemon, "showdownId")),
                        stringValue(invokeNoArgsQuietly(species, "showdownId")),
                        stringValue(invokeNoArgsQuietly(form, "getName")),
                        stringValue(invokeNoArgsQuietly(form, "showdownId")),
                        elementalTypes(form)
                ));
            }
        }
        return List.copyOf(entries);
    }

    private static List<String> elementalTypes(Object form) {
        Object types = invokeNoArgsQuietly(form, "getTypes");
        if (!(types instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object type : iterable) {
            String name = stringValue(invokeNoArgsQuietly(type, "getName"));
            result.add(name.isBlank() ? String.valueOf(type) : name);
        }
        return List.copyOf(result);
    }

    private static boolean isPurePlayerPvp(Object battle) {
        try {
            Object actors = invokeNoArgs(battle, "getActors");
            if (!(actors instanceof Iterable<?> iterable)) {
                return false;
            }
            int count = 0;
            for (Object actor : iterable) {
                count++;
                if (!actorTypeIs(actor, "PLAYER")) {
                    return false;
                }
            }
            return count >= 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean actorTypeIs(Object actor, String expected) {
        Object type = invokeNoArgsQuietly(actor, "getType");
        return type != null && expected.equalsIgnoreCase(type.toString());
    }

    private static void logDex(JsonObject root, long run) {
        JsonObject dex = object(root, "dex");
        CobbleKantoServerFixes.LOGGER.info(
                "[CKT-GEN3-DIAG] #{} DEX runtime: currentMod={} gen={} registryAvailable={} registryError={}",
                run,
                string(dex, "mod"),
                integer(dex, "gen"),
                bool(dex, "registryAvailable"),
                string(dex, "registryError")
        );
    }

    private static void logSpecies(JsonObject root, long run) {
        JsonArray species = root.getAsJsonArray("species");
        if (species == null) {
            return;
        }
        for (JsonElement element : species) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3-DIAG] #{} SPECIES {} | resolved={} | registry={} | historical={} | registryExists={} | Dragon eff={} immune={} | Dark eff={} | Ghost eff={}",
                    run,
                    string(entry, "id"),
                    array(entry, "resolvedTypes"),
                    array(entry, "registryTypes"),
                    array(entry, "historicalTypes"),
                    bool(entry, "registryExists"),
                    jsonValue(entry, "dragonEffectiveness"),
                    jsonValue(entry, "dragonImmunity"),
                    jsonValue(entry, "darkEffectiveness"),
                    jsonValue(entry, "ghostEffectiveness")
            );
        }
    }

    private static void logSteel(JsonObject root, long run) {
        JsonObject steel = object(root, "steel");
        CobbleKantoServerFixes.LOGGER.info(
                "[CKT-GEN3-DIAG] #{} STEEL | effective damageTaken Dark={} Ghost={} | rawGen3 Dark={} Ghost={} | effectiveness Dark={} Ghost={} | immunity Dark={} Ghost={}",
                run,
                jsonValue(steel, "effectiveDarkDamageTaken"),
                jsonValue(steel, "effectiveGhostDamageTaken"),
                jsonValue(steel, "rawDarkDamageTaken"),
                jsonValue(steel, "rawGhostDamageTaken"),
                jsonValue(steel, "darkEffectiveness"),
                jsonValue(steel, "ghostEffectiveness"),
                jsonValue(steel, "darkImmunity"),
                jsonValue(steel, "ghostImmunity")
        );
    }

    private static void logSplit(JsonObject root, long run) {
        JsonObject split = object(root, "split");
        CobbleKantoServerFixes.LOGGER.info(
                "[CKT-GEN3-DIAG] #{} SPLIT | Shadow Ball resolved={} historical={} | Bite resolved={} historical={}",
                run,
                string(split, "shadowBallResolvedCategory"),
                string(split, "shadowBallHistoricalCategory"),
                string(split, "biteResolvedCategory"),
                string(split, "biteHistoricalCategory")
        );
    }

    private static JsonObject species(JsonObject root, String id) {
        JsonArray values = root == null ? null : root.getAsJsonArray("species");
        if (values == null) {
            return new JsonObject();
        }
        for (JsonElement value : values) {
            if (value.isJsonObject() && id.equalsIgnoreCase(string(value.getAsJsonObject(), "id"))) {
                return value.getAsJsonObject();
            }
        }
        return new JsonObject();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? -1 : value.getAsInt();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static List<String> array(JsonObject object, String key) {
        JsonArray array = object == null ? null : object.getAsJsonArray(key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : array) {
            values.add(value.isJsonNull() ? "null" : value.getAsString());
        }
        return List.copyOf(values);
    }

    private static String jsonValue(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "null" : value.toString();
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        if (target == null) {
            throw new NullPointerException(name + " target is null");
        }
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invokeNoArgsQuietly(Object target, String name) {
        try {
            return invokeNoArgs(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object[] args) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 160 ? message.substring(0, 157) + "..." : message;
    }

    public record EngineReadiness(boolean ok, String message) {
        public static EngineReadiness ok(String message) {
            return new EngineReadiness(true, message);
        }

        public static EngineReadiness fail(String message) {
            return new EngineReadiness(false, message);
        }
    }

    private record TeamEntry(
            String sentShowdownId,
            String baseSpeciesId,
            String formName,
            String formShowdownId,
            List<String> formTypes
    ) {
        @Override
        public String toString() {
            return "sent=" + sentShowdownId
                    + "/base=" + baseSpeciesId
                    + "/form=" + formName
                    + "/formId=" + formShowdownId
                    + "/types=" + formTypes;
        }
    }
}
