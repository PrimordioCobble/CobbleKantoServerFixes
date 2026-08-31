package net.crulim.cobblekantoserverfixes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, runtime-only legality gates for CobbleKanto's Gen 2/3 tournament PvP.
 *
 * <p>Four independent locks exist and always start OFF after a server boot:</p>
 * <ul>
 *   <li>AbilityLock: ability must be legal for the species in Generation 3.</li>
 *   <li>MoveLock: generation-only FUTURE gate. A move is blocked only when Showdown data proves
 *       it was introduced in Gen 4+; no species learnset/provenance check is performed.</li>
 *   <li>ItemLock: generation-only FUTURE gate. A held item is blocked only when Showdown data
 *       proves it was introduced in Gen 4+.</li>
 *   <li>FormLock: non-base forms must exist in Generation 3. The CobbleKanto datapack's synthetic
 *       "Legacy" form is an explicit technical exception and is accepted as-is.</li>
 * </ul>
 *
 * <p>The source of truth is the exact Pokémon Showdown instance booted by Cobblemon. No ability,
 * move or item is ever changed automatically.</p>
 *
 * <p>Safety policy:</p>
 * <ul>
 *   <li>Activation is fail-closed: a lock refuses to turn ON unless embedded self-tests pass.</li>
 *   <li>Runtime infrastructure failure is fail-open: legality locks turn OFF and the battle is allowed.</li>
 *   <li>Confirmed violations cancel BATTLE_STARTED_PRE before Showdown launches the battle.</li>
 * </ul>
 */
public final class TournamentGen3LegalityBridge {
    private static final String SHOWDOWN_SERVICE_CLASS = "com.cobblemon.mod.common.battles.runner.ShowdownService";
    private static final String HELD_ITEM_MANAGER_CLASS = "com.cobblemon.mod.common.pokemon.helditem.CobblemonHeldItemManager";

    private static final String JS_BRIDGE_SOURCE = """
            (() => {
              if (globalThis.__cksLegacyLegalityValidateV5 && globalThis.__cksLegacyLegalitySelfTestV5) return;

              const {Dex, toID} = require('./sim/dex');
              const dex3 = Dex.mod('gen3');
              const dex2 = Dex.mod('gen2');
              const dex1 = Dex.mod('gen1');

              function uniq(values) {
                return [...new Set(values.filter(Boolean))];
              }

              // IMPORTANT: Cobblemon's custom Showdown species registry takes precedence inside
              // dex.species.get(). In Cobblemon 1.7.x that registered Species object can have an
              // unpopulated ability map (effectively "No Ability"). For historical legality we
              // therefore read the generation-specific, already-inherited Showdown Pokedex table
              // directly. This is the same data dex.species.get() would use without the Cobblemon
              // registry override.
              function rawSpeciesData(dex, speciesId) {
                const id = toID(speciesId || '');
                if (!id || !dex || !dex.data || !dex.data.Pokedex) return null;
                return dex.data.Pokedex[id] || null;
              }

              function rawAbilityGeneration(dex, abilityName) {
                const id = toID(abilityName || '');
                const data = id && dex && dex.data && dex.data.Abilities ? dex.data.Abilities[id] : null;
                if (!data) return 0;
                if (Number.isFinite(data.gen) && data.gen > 0) return data.gen;
                const num = Number(data.num || 0);
                if (num >= 268) return 9;
                if (num >= 234) return 8;
                if (num >= 192) return 7;
                if (num >= 165) return 6;
                if (num >= 124) return 5;
                if (num >= 77) return 4;
                if (num >= 1) return 3;
                return 0;
              }

              function allowedAbilitiesGen3(speciesId) {
                const species = rawSpeciesData(dex3, speciesId);
                if (!species) return [];
                const abilities = {...(species.abilities || {})};

                // Mirrors Pokemon Showdown's DexSpecies Gen 3 post-processing:
                // hidden abilities do not exist before Gen 5; ability slot 1 is removed in Gen 3
                // when that ability itself was introduced in Gen 4.
                delete abilities.H;
                if (abilities['1'] && rawAbilityGeneration(dex3, abilities['1']) === 4) {
                  delete abilities['1'];
                }
                return uniq(Object.values(abilities));
              }

              function displayAbility(rawAbility) {
                const id = toID(rawAbility || '');
                const data = id && dex3.data && dex3.data.Abilities ? dex3.data.Abilities[id] : null;
                return data && data.name ? data.name : String(rawAbility || '');
              }

              function rawGenerationData(tableName, rawId) {
                const id = toID(rawId || '');
                const table = id && dex3 && dex3.data ? dex3.data[tableName] : null;
                return table && table[id] ? table[id] : null;
              }

              // IMPORTANT: raw dex.data.Moves entries can legitimately have gen=0. Showdown's
              // DataMove constructor fills the introduction generation from the canonical move
              // number (1-165=Gen1, 166-251=Gen2, 252-354=Gen3, 355-467=Gen4, ...), and
              // DexMoves.getByID also marks entries newer than the current mod as Future. Always
              // use the official resolver for FUTURE-only move decisions instead of reading the
              // raw table directly.
              function resolvedMoveData(rawId) {
                const id = toID(rawId || '');
                if (!id) return null;
                const move = dex3.moves.get(id);
                return move && move.exists ? move : null;
              }

              function resolvedItemData(rawId) {
                const id = toID(rawId || '');
                if (!id) return null;
                const item = dex3.items.get(id);
                return item && item.exists ? item : null;
              }

              function generationNumber(data) {
                return data && Number.isFinite(data.gen) && data.gen > 0 ? Number(data.gen) : 0;
              }

              function isProvenFuture(data) {
                return !!data && (generationNumber(data) > 3 || data.isNonstandard === 'Future');
              }

              function displayDataName(data, fallback) {
                return data && data.name ? data.name : String(fallback || '');
              }

              function validate(payloadJson) {
                const payload = JSON.parse(payloadJson);
                const baseId = payload.species || '';
                const species3 = dex3.species.get(baseId);
                const species2 = dex2.species.get(baseId);
                const species1 = dex1.species.get(baseId);
                const raw3 = rawSpeciesData(dex3, baseId);
                const raw2 = rawSpeciesData(dex2, baseId);
                const raw1 = rawSpeciesData(dex1, baseId);
                function rawSpeciesGeneration(data) {
                  if (!data) return 0;
                  if (Number.isFinite(data.gen) && data.gen > 0) return Number(data.gen);
                  const num = Number(data.num || 0);
                  if (num >= 906) return 9;
                  if (num >= 810) return 8;
                  if (num >= 722) return 7;
                  if (num >= 650) return 6;
                  if (num >= 494) return 5;
                  if (num >= 387) return 4;
                  if (num >= 252) return 3;
                  if (num >= 152) return 2;
                  if (num >= 1) return 1;
                  return 0;
                }

                function isHistoricalSpecies(data) {
                  if (!data || data.isNonstandard === 'Future') return false;
                  const gen = rawSpeciesGeneration(data);
                  // Unknown/custom base species are a manual roster concern, not an Ability/Form
                  // lock concern. Only positively identified Gen 1-3 species enter these gates.
                  return gen > 0 && gen <= 3;
                }

                // This flag intentionally means "historical species eligible for Ability/Form
                // validation", NOT "the modern registry knows this Pokémon". Tournament roster
                // legality is manual. Gen 4+ or custom/unknown base species must never be turned
                // into an implicit species ban by AbilityLock/FormLock.
                const speciesExists = !!(
                  isHistoricalSpecies(raw3) || isHistoricalSpecies(raw2) || isHistoricalSpecies(raw1)
                );
                const displaySpecies = raw3 && raw3.name
                  ? raw3.name
                  : (raw2 && raw2.name ? raw2.name : (raw1 && raw1.name ? raw1.name : String(payload.species || '?')));

                const allowed = allowedAbilitiesGen3(baseId);
                const currentAbilityId = toID(payload.ability || '');
                const abilityAllowed = !!(
                  currentAbilityId && allowed.some(a => toID(a) === currentAbilityId)
                );

                const inputMoves = Array.isArray(payload.moves) ? payload.moves.filter(Boolean) : [];
                const illegalMoves = [];

                for (const rawMove of inputMoves) {
                  // FUTURE-ONLY policy: do not validate learnsets, transfer provenance, event
                  // restrictions or cartridge compatibility. We block only when the inherited
                  // Showdown move table positively identifies the move as Gen 4+. If a custom or
                  // unknown move has no trustworthy generation metadata, this lock allows it.
                  const moveData = resolvedMoveData(rawMove);
                  if (!isProvenFuture(moveData)) continue;

                  const moveGen = generationNumber(moveData);
                  illegalMoves.push({
                    move: displayDataName(moveData, rawMove),
                    reason: moveGen > 3
                      ? `golpe é do futuro (Gen ${moveGen})`
                      : 'golpe está marcado como conteúdo Future pelo Showdown'
                  });
                }

                const rawItem = payload.item || '';
                let itemAllowed = true;
                let currentItem = rawItem;
                let itemReason = null;
                if (rawItem) {
                  // Same FUTURE-ONLY rule as moves. Unknown/custom held-item ids are not rejected
                  // merely because an old-generation Dex cannot resolve them.
                  const itemData = resolvedItemData(rawItem);
                  currentItem = displayDataName(itemData, rawItem);
                  if (isProvenFuture(itemData)) {
                    const itemGen = generationNumber(itemData);
                    itemAllowed = false;
                    itemReason = itemGen > 3
                      ? `held item é do futuro (Gen ${itemGen})`
                      : 'held item está marcado como conteúdo Future pelo Showdown';
                  }
                }

                const formName = String(payload.formName || 'Normal');
                const formId = String(payload.formShowdownId || baseId);
                const normalizedForm = formName.toLowerCase();
                let formAllowed = normalizedForm === 'normal' || normalizedForm === 'legacy' || toID(formId) === toID(baseId);
                let formReason = null;
                if (!formAllowed) {
                  const formSpecies = dex3.species.get(formId);
                  formAllowed = !!(formSpecies && formSpecies.exists && formSpecies.gen <= 3 && formSpecies.isNonstandard !== 'Future');
                  if (!formAllowed) formReason = 'forma não existe na Gen 3';
                }

                return JSON.stringify({
                  infrastructureError: null,
                  speciesExists,
                  speciesName: displaySpecies,
                  abilityAllowed,
                  currentAbility: displayAbility(payload.ability),
                  allowedAbilities: allowed,
                  illegalMoves,
                  itemAllowed,
                  currentItem,
                  itemReason,
                  formAllowed,
                  currentForm: formName,
                  formReason
                });
              }

              function selfTest() {
                const pAbilities = allowedAbilitiesGen3('pelipper');
                const gAbilities = allowedAbilitiesGen3('gardevoir');
                const tAbilities = allowedAbilitiesGen3('torkoal');

                // Diagnostic only: demonstrates whether Cobblemon's runtime species registry is
                // shadowing the historical Pokedex ability data in this server build.
                const registryPelipper = dex3.species.get('pelipper');
                const registryPelipperAbilities = uniq(Object.values((registryPelipper && registryPelipper.abilities) || {}));

                const surf = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['surf'], item:'', level:100})));
                const hydroPump = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['hydropump'], item:'', level:100})));
                const hurricane = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['hurricane'], item:'', level:100})));
                const rainDish = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'raindish', moves:['surf'], item:'', level:100})));
                const flamethrower = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['flamethrower'], item:'', level:100})));
                const telepathy = JSON.parse(validate(JSON.stringify({species:'gardevoir', formName:'Normal', formShowdownId:'gardevoir', ability:'telepathy', moves:['psychic'], item:'', level:100})));
                const droughtTorkoal = JSON.parse(validate(JSON.stringify({species:'torkoal', formName:'Normal', formShowdownId:'torkoal', ability:'drought', moves:['flamethrower'], item:'', level:100})));
                const choiceBand = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['surf'], item:'choiceband', level:100})));
                const choiceScarf = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['surf'], item:'choicescarf', level:100})));
                const futureForm = JSON.parse(validate(JSON.stringify({species:'gardevoir', formName:'Mega', formShowdownId:'gardevoirmega', ability:'synchronize', moves:['psychic'], item:'', level:100})));
                const legacyForm = JSON.parse(validate(JSON.stringify({species:'gardevoir', formName:'Legacy', formShowdownId:'gardevoirlegacy', ability:'synchronize', moves:['psychic'], item:'', level:100})));
                const taurosFissure = JSON.parse(validate(JSON.stringify({species:'tauros', formName:'Normal', formShowdownId:'tauros', ability:'intimidate', moves:['fissure'], item:'', level:100})));
                const unknownMove = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['cks_custom_unknown_move'], item:'', level:100})));
                const unknownItem = JSON.parse(validate(JSON.stringify({species:'pelipper', formName:'Normal', formShowdownId:'pelipper', ability:'keeneye', moves:['surf'], item:'cks_custom_unknown_item', level:100})));
                const futureSpecies = JSON.parse(validate(JSON.stringify({species:'lucario', formName:'Normal', formShowdownId:'lucario', ability:'steadfast', moves:['quickattack'], item:'', level:100})));
                const rawHydroPump = resolvedMoveData('hydropump');
                const rawHurricane = resolvedMoveData('hurricane');
                const rawChoiceBand = resolvedItemData('choiceband');
                const rawChoiceScarf = resolvedItemData('choicescarf');

                return JSON.stringify({
                  gen3: dex3.gen,
                  gen2: dex2.gen,
                  gen1: dex1.gen,
                  abilitySource: 'dex3.data.Pokedex',
                  moveGenerationSource: 'dex3.moves.get',
                  itemGenerationSource: 'dex3.items.get',
                  registryPelipperAbilities,
                  pelipperAbilities: pAbilities,
                  gardevoirAbilities: gAbilities,
                  torkoalAbilities: tAbilities,
                  keenEyeLegal: surf.abilityAllowed,
                  surfLegal: surf.illegalMoves.length === 0,
                  hydroPumpLegal: hydroPump.illegalMoves.length === 0,
                  hurricaneIllegal: hurricane.illegalMoves.length > 0,
                  rainDishIllegal: !rainDish.abilityAllowed,
                  flamethrowerPastAllowed: flamethrower.illegalMoves.length === 0,
                  telepathyIllegal: !telepathy.abilityAllowed,
                  droughtTorkoalIllegal: !droughtTorkoal.abilityAllowed,
                  choiceBandLegal: choiceBand.itemAllowed,
                  choiceScarfIllegal: !choiceScarf.itemAllowed,
                  futureFormIllegal: !futureForm.formAllowed,
                  legacyFormLegal: legacyForm.formAllowed,
                  taurosFissureLegal: taurosFissure.illegalMoves.length === 0,
                  unknownMoveAllowed: unknownMove.illegalMoves.length === 0,
                  unknownItemAllowed: unknownItem.itemAllowed,
                  futureSpeciesRosterIgnored: !futureSpecies.speciesExists,
                  hydroPumpGen: generationNumber(rawHydroPump),
                  hurricaneGen: generationNumber(rawHurricane),
                  choiceBandGen: generationNumber(rawChoiceBand),
                  choiceScarfGen: generationNumber(rawChoiceScarf)
                });
              }

              globalThis.__cksLegacyLegalityValidateV5 = validate;
              globalThis.__cksLegacyLegalitySelfTestV5 = selfTest;
            })();
            """;

    private static volatile boolean abilityLockEnabled;
    private static volatile boolean moveLockEnabled;
    private static volatile boolean itemLockEnabled;
    private static volatile boolean formLockEnabled;

    private static volatile boolean validatorReady;
    private static volatile String validatorState = "não validado nesta inicialização";
    private static volatile String abilitySelfTestState = "não executado";
    private static volatile String moveSelfTestState = "não executado";
    private static volatile String itemSelfTestState = "não executado";
    private static volatile String formSelfTestState = "não executado";
    private static volatile String lastResult = "nenhuma checagem nesta inicialização";

    private static volatile Object validateFunction;
    private static volatile Object selfTestFunction;
    private static volatile Method valueExecute;
    private static volatile Method valueAsString;

    private static volatile boolean heldItemResolverReady;
    private static volatile String heldItemResolverState = "não validado nesta inicialização";
    private static volatile Object heldItemManagerInstance;
    private static volatile Method heldItemShowdownIdMethod;

    private static final AtomicLong validationRuns = new AtomicLong();
    private static final AtomicLong blockedBattles = new AtomicLong();
    private static final AtomicLong runtimeBypasses = new AtomicLong();
    private static final AtomicLong operatorReports = new AtomicLong();

    private TournamentGen3LegalityBridge() {
    }

    public static synchronized EnableResult enableAbilityLock() {
        EnableResult prerequisite = prerequisite("abilitylock");
        if (!prerequisite.ok()) {
            abilityLockEnabled = false;
            return prerequisite;
        }
        abilityLockEnabled = true;
        CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Ability lock ON.");
        return EnableResult.ok("Ability lock ON; self-test do Showdown passou.");
    }

    public static synchronized EnableResult enableMoveLock() {
        EnableResult prerequisite = prerequisite("movelock");
        if (!prerequisite.ok()) {
            moveLockEnabled = false;
            return prerequisite;
        }
        moveLockEnabled = true;
        CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Move lock ON (FUTURE-ONLY: Gen 1/2/3 allowed; proven Gen 4+ blocked; no learnset gate).");
        return EnableResult.ok("Move lock ON; somente golpes comprovadamente Gen 4+ serão bloqueados. Gen 1/2/3 e desconhecidos/custom passam.");
    }

    public static synchronized EnableResult enableItemLock() {
        EnableResult prerequisite = prerequisite("itemlock");
        if (!prerequisite.ok()) {
            itemLockEnabled = false;
            return prerequisite;
        }
        EnableResult resolver = ensureHeldItemResolverReady();
        if (!resolver.ok()) {
            itemLockEnabled = false;
            return resolver;
        }
        itemLockEnabled = true;
        CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Item lock ON.");
        return EnableResult.ok("Item lock ON; held items de batalha posteriores à Gen 3 serão bloqueados.");
    }

    public static synchronized EnableResult enableFormLock() {
        EnableResult prerequisite = prerequisite("formlock");
        if (!prerequisite.ok()) {
            formLockEnabled = false;
            return prerequisite;
        }
        formLockEnabled = true;
        CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Form lock ON.");
        return EnableResult.ok("Form lock ON; formas posteriores à Gen 3 serão bloqueadas; forma Legacy do datapack é aceita.");
    }

    private static EnableResult prerequisite(String lock) {
        if (!TournamentGen3BattleBridge.isEnabled()) {
            return EnableResult.fail("ligue /cktournament gen3 on antes do " + lock);
        }
        EnableResult infrastructure = ensureValidatorReady();
        if (!infrastructure.ok()) return infrastructure;
        return runLockSelfTest(lock);
    }

    public static synchronized void disableAbilityLock(String reason) {
        boolean was = abilityLockEnabled;
        abilityLockEnabled = false;
        if (was) CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Ability lock OFF ({}).", reason);
    }

    public static synchronized void disableMoveLock(String reason) {
        boolean was = moveLockEnabled;
        moveLockEnabled = false;
        if (was) CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Move lock OFF ({}).", reason);
    }

    public static synchronized void disableItemLock(String reason) {
        boolean was = itemLockEnabled;
        itemLockEnabled = false;
        if (was) CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Item lock OFF ({}).", reason);
    }

    public static synchronized void disableFormLock(String reason) {
        boolean was = formLockEnabled;
        formLockEnabled = false;
        if (was) CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3-LEGAL] Form lock OFF ({}).", reason);
    }

    public static synchronized void disableAll(String reason) {
        disableAbilityLock(reason);
        disableMoveLock(reason);
        disableItemLock(reason);
        disableFormLock(reason);
    }

    public static boolean isAbilityLockEnabled() { return abilityLockEnabled; }
    public static boolean isMoveLockEnabled() { return moveLockEnabled; }
    public static boolean isItemLockEnabled() { return itemLockEnabled; }
    public static boolean isFormLockEnabled() { return formLockEnabled; }

    public static String statusSummary() {
        return "abilityLock=" + onOff(abilityLockEnabled)
                + ",moveLock=" + onOff(moveLockEnabled)
                + ",itemLock=" + onOff(itemLockEnabled)
                + ",formLock=" + onOff(formLockEnabled)
                + ",validator=" + (validatorReady ? "READY" : validatorState)
                + ",selfTests=A:" + abilitySelfTestState
                + "/M:" + moveSelfTestState
                + "/I:" + itemSelfTestState
                + "/F:" + formSelfTestState
                + ",itemResolver=" + (heldItemResolverReady ? "READY" : heldItemResolverState)
                + ",movePolicy=FUTURE_ONLY"
                + ",itemPolicy=FUTURE_ONLY"
                + ",naturePolicy=UNRESTRICTED"
                + ",checks=" + validationRuns.get()
                + ",blocked=" + blockedBattles.get()
                + ",bypass=" + runtimeBypasses.get()
                + ",opReports=" + operatorReports.get()
                + ",last=" + lastResult;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    /** BATTLE_STARTED_PRE hook. Returns true only when this bridge canceled the battle. */
    public static boolean inspectAndMaybeCancel(Object event, MinecraftServer server) {
        if ((!abilityLockEnabled && !moveLockEnabled && !itemLockEnabled && !formLockEnabled)
                || !TournamentGen3BattleBridge.isEnabled() || event == null) {
            return false;
        }

        Object battle = invokeNoArgsQuietly(event, "getBattle");
        if (battle == null || !isPurePlayerPvp(battle)) {
            return false;
        }

        try {
            EnableResult ready = ensureValidatorReady();
            if (!ready.ok()) {
                runtimeFailOpen("validator indisponível no PRE: " + ready.message(), null, battle, server);
                return false;
            }
            if (itemLockEnabled) {
                EnableResult resolver = ensureHeldItemResolverReady();
                if (!resolver.ok()) {
                    runtimeFailOpen("resolver de held item indisponível no PRE: " + resolver.message(), null, battle, server);
                    return false;
                }
            }

            List<Problem> problems = inspectBattle(
                    battle,
                    abilityLockEnabled,
                    moveLockEnabled,
                    itemLockEnabled,
                    formLockEnabled
            );
            validationRuns.incrementAndGet();
            if (problems.isEmpty()) {
                lastResult = "OK: último PvP passou nos locks ativos";
                return false;
            }

            cancelEvent(event, problems.size());
            long count = blockedBattles.incrementAndGet();
            lastResult = "BLOCKED #" + count + ": " + problems.size() + " incompatibilidade(s)";
            sendProblems(battle, problems, server);
            CobbleKantoServerFixes.LOGGER.warn(
                    "[CKT-GEN3-LEGAL] Blocked PvP before launch: {} incompatibility(s). Details: {}",
                    problems.size(),
                    problems
            );
            return true;
        } catch (Throwable throwable) {
            runtimeFailOpen("exceção inesperada durante validação PRE", throwable, battle, server);
            return false;
        }
    }

    private static List<Problem> inspectBattle(
            Object battle,
            boolean checkAbility,
            boolean checkMoves,
            boolean checkItems,
            boolean checkForms
    ) throws Exception {
        Object actorsValue = invokeNoArgs(battle, "getActors");
        if (!(actorsValue instanceof Iterable<?> actors)) {
            throw new IllegalStateException("PokemonBattle.getActors() não retornou Iterable");
        }

        List<Problem> problems = new ArrayList<>();
        for (Object actor : actors) {
            if (!actorTypeIs(actor, "PLAYER")) continue;

            UUID playerUuid = asUuid(invokeNoArgs(actor, "getUuid"));
            if (playerUuid == null) {
                throw new IllegalStateException("Player BattleActor sem UUID; validação liberada por segurança");
            }
            String playerName = playerName(playerUuid, actor);
            Object pokemonListValue = invokeNoArgs(actor, "getPokemonList");
            if (!(pokemonListValue instanceof Iterable<?> pokemonList)) {
                throw new IllegalStateException("Player BattleActor.getPokemonList() não retornou Iterable");
            }

            for (Object battlePokemon : pokemonList) {
                Object originalPokemon = invokeNoArgs(battlePokemon, "getOriginalPokemon");
                if (originalPokemon == null) throw new IllegalStateException("BattlePokemon sem originalPokemon");

                PokemonSnapshot snapshot = snapshot(playerUuid, playerName, originalPokemon, checkItems);
                ValidationResult result = validate(snapshot);

                // There is deliberately NO species-generation gate. Tournament staff checks the
                // Gen 2/3 species roster manually. Move/Item locks must remain independent from
                // species legality, and an unknown/custom species must never be rejected by them.
                if (checkAbility && result.speciesExists() && !result.abilityAllowed()) {
                    problems.add(Problem.ability(
                            snapshot.playerUuid(), snapshot.playerName(), snapshot.partySlot(), result.speciesName(),
                            result.currentAbility().isBlank() ? snapshot.abilityName() : result.currentAbility(),
                            result.allowedAbilities()
                    ));
                }

                if (checkMoves) {
                    for (MoveProblem move : result.illegalMoves()) {
                        problems.add(Problem.move(
                                snapshot.playerUuid(), snapshot.playerName(), snapshot.partySlot(), result.speciesName(),
                                move.move(), simplifyMoveReason(move.reason())
                        ));
                    }
                }

                if (checkItems && !result.itemAllowed()) {
                    problems.add(Problem.item(
                            snapshot.playerUuid(), snapshot.playerName(), snapshot.partySlot(), result.speciesName(),
                            result.currentItem().isBlank() ? snapshot.heldItemShowdownId() : result.currentItem(),
                            result.itemReason() == null ? "held item é conteúdo do futuro (Gen 4+)" : result.itemReason()
                    ));
                }

                if (checkForms && result.speciesExists() && !result.formAllowed()) {
                    problems.add(Problem.form(
                            snapshot.playerUuid(), snapshot.playerName(), snapshot.partySlot(), result.speciesName(),
                            result.currentForm().isBlank() ? snapshot.formName() : result.currentForm(),
                            result.formReason() == null ? "forma não existe na Gen 3" : result.formReason()
                    ));
                }
            }
        }
        return problems;
    }

    private static PokemonSnapshot snapshot(UUID playerUuid, String playerName, Object pokemon, boolean resolveItem) throws Exception {
        Object species = invokeNoArgs(pokemon, "getSpecies");
        if (species == null) throw new IllegalStateException("Pokemon.getSpecies() retornou null");
        Object baseShowdownId = invokeNoArgs(species, "showdownId");
        if (baseShowdownId == null || baseShowdownId.toString().isBlank()) {
            throw new IllegalStateException("Species.showdownId() vazio");
        }
        String speciesId = baseShowdownId.toString();

        Object form = invokeNoArgsQuietly(pokemon, "getForm");
        String formName = stringValue(invokeNoArgsQuietly(form, "getName"), "Normal");
        String formShowdownId = stringValue(invokeNoArgsQuietly(form, "showdownId"), speciesId);
        // The legacy typing datapack intentionally creates an aspectless synthetic form named Legacy.
        // It is battle-equivalent to the base species and must not be sent to Showdown as e.g. gardevoirlegacy.
        if ("legacy".equalsIgnoreCase(formName)) {
            formShowdownId = speciesId;
        }

        Object ability = invokeNoArgs(pokemon, "getAbility");
        Object abilityName = ability == null ? null : invokeNoArgs(ability, "getName");

        List<String> moves = new ArrayList<>();
        Object moveSet = invokeNoArgs(pokemon, "getMoveSet");
        Object moveValues = moveSet == null ? null : invokeNoArgs(moveSet, "getMoves");
        if (moveValues instanceof Iterable<?> iterable) {
            for (Object move : iterable) addMoveName(moves, move);
        } else if (moveSet instanceof Iterable<?> iterable) {
            for (Object move : iterable) addMoveName(moves, move);
        } else {
            throw new IllegalStateException("Pokemon MoveSet não pôde ser enumerado");
        }

        int level = 100;
        Object levelValue = invokeNoArgsQuietly(pokemon, "getLevel");
        if (levelValue instanceof Number number) level = Math.max(1, Math.min(100, number.intValue()));

        int partySlot = resolvePartySlot(pokemon);
        if (partySlot <= 0) {
            throw new IllegalStateException(
                    "Não foi possível resolver o slot exato da party de " + playerName + "; validação liberada por segurança"
            );
        }

        String heldItemShowdownId = resolveItem ? resolveHeldItemShowdownId(pokemon) : "";

        return new PokemonSnapshot(
                playerUuid,
                playerName,
                partySlot,
                speciesId,
                formShowdownId,
                formName,
                abilityName == null ? "" : abilityName.toString(),
                List.copyOf(moves),
                level,
                heldItemShowdownId
        );
    }

    private static void addMoveName(List<String> moves, Object move) throws Exception {
        Object name = invokeNoArgs(move, "getName");
        if (name != null && !name.toString().isBlank()) moves.add(name.toString());
    }

    private static String resolveHeldItemShowdownId(Object pokemon) throws Exception {
        Object stackValue = invokeNoArgs(pokemon, "heldItem");
        if (!(stackValue instanceof ItemStack stack) || stack.isEmpty()) return "";

        EnableResult resolver = ensureHeldItemResolverReady();
        if (!resolver.ok()) throw new IllegalStateException(resolver.message());

        Object result = heldItemShowdownIdMethod.invoke(heldItemManagerInstance, stack);
        // null means Cobblemon itself does not treat this arbitrary Minecraft item as a battle held item.
        // Such an item has no Showdown battle effect and is intentionally ignored by ItemLock.
        return result == null ? "" : result.toString();
    }

    private static ValidationResult validate(PokemonSnapshot snapshot) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("species", snapshot.speciesId());
        payload.addProperty("formShowdownId", snapshot.formShowdownId());
        payload.addProperty("formName", snapshot.formName());
        payload.addProperty("ability", snapshot.abilityName());
        payload.addProperty("level", snapshot.level());
        payload.addProperty("item", snapshot.heldItemShowdownId());
        JsonArray moves = new JsonArray();
        snapshot.moves().forEach(moves::add);
        payload.add("moves", moves);

        String raw = executeString(validateFunction, payload.toString());
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        List<String> abilities = new ArrayList<>();
        JsonArray allowed = json.getAsJsonArray("allowedAbilities");
        if (allowed != null) for (JsonElement element : allowed) abilities.add(element.getAsString());

        List<MoveProblem> illegal = new ArrayList<>();
        JsonArray illegalMoves = json.getAsJsonArray("illegalMoves");
        if (illegalMoves != null) {
            for (JsonElement element : illegalMoves) {
                JsonObject move = element.getAsJsonObject();
                illegal.add(new MoveProblem(
                        stringOr(move, "move", "?"),
                        stringOr(move, "reason", "conteúdo do futuro (Gen 4+)")
                ));
            }
        }

        return new ValidationResult(
                boolOr(json, "speciesExists", false),
                stringOr(json, "speciesName", snapshot.speciesId()),
                boolOr(json, "abilityAllowed", false),
                stringOr(json, "currentAbility", snapshot.abilityName()),
                List.copyOf(abilities),
                List.copyOf(illegal),
                boolOr(json, "itemAllowed", true),
                stringOr(json, "currentItem", snapshot.heldItemShowdownId()),
                nullableString(json, "itemReason"),
                boolOr(json, "formAllowed", true),
                stringOr(json, "currentForm", snapshot.formName()),
                nullableString(json, "formReason")
        );
    }

    private static synchronized EnableResult ensureValidatorReady() {
        if (validatorReady && validateFunction != null && selfTestFunction != null) return EnableResult.ok("READY");

        try {
            Object service = getShowdownService();
            if (service == null || !service.getClass().getName().endsWith("GraalShowdownService")) {
                throw new IllegalStateException("ShowdownService ativo não é GraalShowdownService: "
                        + (service == null ? "null" : service.getClass().getName()));
            }

            Object context = invokeNoArgs(service, "getContext");
            if (context == null) throw new IllegalStateException("GraalShowdownService.context ainda não está disponível");

            Method eval = findMethod(context.getClass(), "eval", String.class, CharSequence.class);
            if (eval == null) eval = findCompatibleMethod(context.getClass(), "eval", 2);
            if (eval == null) throw new NoSuchMethodException(context.getClass().getName() + ".eval(language, source)");
            eval.setAccessible(true);
            eval.invoke(context, "js", JS_BRIDGE_SOURCE);

            Object bindings = invoke(context, "getBindings", new Class<?>[]{String.class}, new Object[]{"js"});
            Object validate = invoke(bindings, "getMember", new Class<?>[]{String.class}, new Object[]{"__cksLegacyLegalityValidateV5"});
            Object selfTest = invoke(bindings, "getMember", new Class<?>[]{String.class}, new Object[]{"__cksLegacyLegalitySelfTestV5"});
            if (validate == null || selfTest == null) {
                throw new IllegalStateException("funções JS do validator não ficaram disponíveis nos bindings");
            }

            Method execute = findCompatibleMethod(validate.getClass(), "execute", 1);
            Method asString = findCompatibleMethod(validate.getClass(), "asString", 0);
            if (execute == null || asString == null) throw new NoSuchMethodException("Graal Value.execute/asString não encontrados");
            execute.setAccessible(true);
            asString.setAccessible(true);

            validateFunction = validate;
            selfTestFunction = selfTest;
            valueExecute = execute;
            valueAsString = asString;

            JsonObject test = JsonParser.parseString(executeString(selfTestFunction)).getAsJsonObject();
            verifyCoreSelfTest(test);

            validatorReady = true;
            validatorState = "READY";
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3-LEGAL] Embedded Showdown legality infrastructure READY. "
                            + "Feature self-tests are isolated per lock. selfTest={}",
                    test
            );
            return EnableResult.ok("READY");
        } catch (Throwable throwable) {
            validatorReady = false;
            validateFunction = null;
            selfTestFunction = null;
            valueExecute = null;
            valueAsString = null;
            validatorState = "FAIL:" + throwable.getClass().getSimpleName();
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3-LEGAL] Embedded Showdown legality infrastructure FAILED. Legality locks will refuse activation.",
                    throwable
            );
            return EnableResult.fail("infraestrutura do validator falhou (" + throwable.getClass().getSimpleName() + "); veja o console");
        }
    }

    private static synchronized EnableResult ensureHeldItemResolverReady() {
        if (heldItemResolverReady && heldItemManagerInstance != null && heldItemShowdownIdMethod != null) {
            return EnableResult.ok("READY");
        }
        try {
            Class<?> managerClass = Class.forName(HELD_ITEM_MANAGER_CLASS, false, TournamentGen3LegalityBridge.class.getClassLoader());
            Object instance = managerClass.getField("INSTANCE").get(null);
            Method showdownId = null;
            for (Method method : managerClass.getMethods()) {
                if (!method.getName().equals("showdownId") || method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                if (param.isAssignableFrom(ItemStack.class) || ItemStack.class.isAssignableFrom(param)) {
                    showdownId = method;
                    break;
                }
            }
            if (showdownId == null) throw new NoSuchMethodException("CobblemonHeldItemManager.showdownId(ItemStack)");
            showdownId.setAccessible(true);

            heldItemManagerInstance = instance;
            heldItemShowdownIdMethod = showdownId;
            heldItemResolverReady = true;
            heldItemResolverState = "READY";
            return EnableResult.ok("READY");
        } catch (Throwable throwable) {
            heldItemResolverReady = false;
            heldItemManagerInstance = null;
            heldItemShowdownIdMethod = null;
            heldItemResolverState = "FAIL:" + throwable.getClass().getSimpleName();
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3-LEGAL] Could not validate Cobblemon held-item Showdown resolver. ItemLock will stay OFF.",
                    throwable
            );
            return EnableResult.fail("resolver de held item falhou (" + throwable.getClass().getSimpleName() + "); veja o console");
        }
    }

    private static void verifyCoreSelfTest(JsonObject test) {
        if (!test.has("gen3") || test.get("gen3").getAsInt() != 3
                || !test.has("gen2") || test.get("gen2").getAsInt() != 2
                || !test.has("gen1") || test.get("gen1").getAsInt() != 1) {
            throw new IllegalStateException("validators não estão nos Dex Gen 1/2/3: " + test);
        }
        if (!"dex3.data.Pokedex".equals(stringOr(test, "abilitySource", ""))) {
            throw new IllegalStateException("fonte histórica de abilities inesperada: " + test);
        }
        if (!boolOr(test, "futureSpeciesRosterIgnored", false)) {
            throw new IllegalStateException("policy de roster manual falhou: espécie futura entrou no Ability/Form gate: " + test);
        }
    }

    private static synchronized EnableResult runLockSelfTest(String lock) {
        try {
            if (selfTestFunction == null) {
                throw new IllegalStateException("função de self-test do Showdown não inicializada");
            }
            JsonObject test = JsonParser.parseString(executeString(selfTestFunction)).getAsJsonObject();
            verifyCoreSelfTest(test);
            verifyLockSelfTest(lock, test);
            setLockSelfTestState(lock, "READY");
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3-LEGAL] {} self-test READY. abilitySource={}, registryPelipperAbilities={}, historicalPelipperAbilities={}",
                    lock,
                    stringOr(test, "abilitySource", "?"),
                    test.get("registryPelipperAbilities"),
                    test.get("pelipperAbilities")
            );
            return EnableResult.ok("READY");
        } catch (Throwable throwable) {
            setLockSelfTestState(lock, "FAIL:" + throwable.getClass().getSimpleName());
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3-LEGAL] {} self-test FAILED. Somente este lock recusará ativação.",
                    lock,
                    throwable
            );
            return EnableResult.fail("self-test do " + lock + " falhou ("
                    + throwable.getClass().getSimpleName() + "); veja o console");
        }
    }

    private static void verifyLockSelfTest(String lock, JsonObject test) {
        switch (lock.toLowerCase(Locale.ROOT)) {
            case "abilitylock" -> {
                if (!boolOr(test, "keenEyeLegal", false)
                        || !boolOr(test, "rainDishIllegal", false)
                        || !boolOr(test, "telepathyIllegal", false)
                        || !boolOr(test, "droughtTorkoalIllegal", false)) {
                    throw new IllegalStateException("sentinelas de AbilityLock falharam: " + test);
                }
                Set<String> pelipper = lowerSet(test.getAsJsonArray("pelipperAbilities"));
                if (!pelipper.equals(Set.of("keen eye"))) {
                    throw new IllegalStateException("Pelipper Gen 3 esperado [Keen Eye], recebido " + pelipper);
                }
                Set<String> gardevoir = lowerSet(test.getAsJsonArray("gardevoirAbilities"));
                if (!gardevoir.equals(Set.of("synchronize", "trace"))) {
                    throw new IllegalStateException("Gardevoir Gen 3 esperado [Synchronize, Trace], recebido " + gardevoir);
                }
                Set<String> torkoal = lowerSet(test.getAsJsonArray("torkoalAbilities"));
                if (!torkoal.equals(Set.of("white smoke"))) {
                    throw new IllegalStateException("Torkoal Gen 3 esperado [White Smoke], recebido " + torkoal);
                }
            }
            case "movelock" -> {
                if (!boolOr(test, "surfLegal", false)
                        || !boolOr(test, "hydroPumpLegal", false)
                        || !boolOr(test, "hurricaneIllegal", false)
                        || !boolOr(test, "flamethrowerPastAllowed", false)
                        || !boolOr(test, "taurosFissureLegal", false)
                        || !boolOr(test, "unknownMoveAllowed", false)
                        || intOr(test, "hydroPumpGen", -1) != 1
                        || intOr(test, "hurricaneGen", -1) <= 3) {
                    throw new IllegalStateException("sentinelas FUTURE-ONLY do MoveLock falharam: " + test);
                }
            }
            case "itemlock" -> {
                if (!boolOr(test, "choiceBandLegal", false)
                        || !boolOr(test, "choiceScarfIllegal", false)
                        || !boolOr(test, "unknownItemAllowed", false)
                        || intOr(test, "choiceBandGen", -1) > 3
                        || intOr(test, "choiceBandGen", -1) <= 0
                        || intOr(test, "choiceScarfGen", -1) <= 3) {
                    throw new IllegalStateException("sentinelas FUTURE-ONLY do ItemLock falharam: " + test);
                }
            }
            case "formlock" -> {
                if (!boolOr(test, "futureFormIllegal", false)
                        || !boolOr(test, "legacyFormLegal", false)) {
                    throw new IllegalStateException("sentinelas de FormLock falharam: " + test);
                }
            }
            default -> throw new IllegalArgumentException("lock desconhecido: " + lock);
        }
    }

    private static void setLockSelfTestState(String lock, String state) {
        switch (lock.toLowerCase(Locale.ROOT)) {
            case "abilitylock" -> abilitySelfTestState = state;
            case "movelock" -> moveSelfTestState = state;
            case "itemlock" -> itemSelfTestState = state;
            case "formlock" -> formSelfTestState = state;
            default -> { }
        }
    }

    private static Object getShowdownService() throws Exception {
        Class<?> serviceClass = Class.forName(SHOWDOWN_SERVICE_CLASS, false, TournamentGen3LegalityBridge.class.getClassLoader());
        Object companion = serviceClass.getField("Companion").get(null);
        return invokeNoArgs(companion, "getService");
    }

    private static String executeString(Object function, Object... args) throws Exception {
        if (function == null || valueExecute == null || valueAsString == null) {
            throw new IllegalStateException("Graal function não inicializada");
        }
        Object result = valueExecute.invoke(function, new Object[]{args});
        if (result == null) throw new IllegalStateException("Graal function retornou null");
        Method asString = result.getClass() == function.getClass()
                ? valueAsString
                : findCompatibleMethod(result.getClass(), "asString", 0);
        if (asString == null) throw new NoSuchMethodException(result.getClass().getName() + ".asString()");
        asString.setAccessible(true);
        return String.valueOf(asString.invoke(result));
    }

    private static void runtimeFailOpen(String message, Throwable throwable, Object battle, MinecraftServer server) {
        boolean hadAbility = abilityLockEnabled;
        boolean hadMoves = moveLockEnabled;
        boolean hadItems = itemLockEnabled;
        boolean hadForms = formLockEnabled;
        disableAll("runtime-fail-open");
        validatorReady = false;
        validatorState = "FAIL-RUNTIME";
        long count = runtimeBypasses.incrementAndGet();
        lastResult = "BYPASS #" + count + ": " + message;

        if (throwable == null) {
            CobbleKantoServerFixes.LOGGER.error("[CKT-GEN3-LEGAL] {}. Locks disabled FAIL-OPEN; battle will be allowed.", message);
        } else {
            CobbleKantoServerFixes.LOGGER.error("[CKT-GEN3-LEGAL] {}. Locks disabled FAIL-OPEN; battle will be allowed.", message, throwable);
        }

        List<String> names = new ArrayList<>();
        if (hadAbility) names.add("ability");
        if (hadMoves) names.add("moves");
        if (hadItems) names.add("items");
        if (hadForms) names.add("forms");
        String locks = names.isEmpty() ? "legality" : String.join("+", names);
        broadcastToBattlePlayers(
                battle,
                server,
                Text.literal("[CKT-GEN3] Validator " + locks + " falhou internamente e foi DESLIGADO; esta batalha foi LIBERADA. Avise a staff.")
                        .formatted(Formatting.RED)
        );
        broadcastToOnlineOperators(
                server,
                Text.literal(
                        "[CKT-GEN3-ADM] FAIL-OPEN: validator " + locks + " falhou internamente e foi desligado. "
                                + "A batalha foi LIBERADA. Motivo: " + message + ". Veja o console para detalhes."
                ).formatted(Formatting.RED)
        );
    }

    private static void cancelEvent(Object event, int count) throws Exception {
        Method cancel = findCompatibleMethod(event.getClass(), "cancel", 0);
        if (cancel == null) throw new NoSuchMethodException(event.getClass().getName() + ".cancel()");
        cancel.setAccessible(true);

        Method setReason = null;
        for (Method method : event.getClass().getMethods()) {
            if (method.getName().equals("setReason") && method.getParameterCount() == 1) {
                setReason = method;
                break;
            }
        }
        if (setReason != null) {
            setReason.setAccessible(true);
            setReason.invoke(event, Text.literal(
                    "[CKT-GEN3] Batalha bloqueada: " + count + " incompatibilidade(s). Veja os detalhes no chat."
            ));
        }
        cancel.invoke(event);
    }

    private static void sendProblems(Object battle, List<Problem> problems, MinecraftServer server) {
        if (server == null || problems.isEmpty()) return;

        broadcastToBattlePlayers(
                battle,
                server,
                Text.literal(
                        "[CKT-GEN3] Batalha bloqueada — " + problems.size()
                                + " incompatibilidade(s). Cada jogador afetado recebeu SOMENTE os detalhes do próprio time."
                ).formatted(Formatting.RED)
        );

        sendFullProblemReportToOnlineOperators(problems, server);

        Map<UUID, List<Problem>> byPlayer = new LinkedHashMap<>();
        for (Problem problem : problems) {
            if (problem.playerUuid() == null) continue;
            byPlayer.computeIfAbsent(problem.playerUuid(), ignored -> new ArrayList<>()).add(problem);
        }

        for (Map.Entry<UUID, List<Problem>> entry : byPlayer.entrySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            List<Problem> playerProblems = entry.getValue();
            String playerName = playerProblems.get(0).playerName();
            player.sendMessage(
                    Text.literal("[CKT-GEN3] Seu time tem " + playerProblems.size() + " incompatibilidade(s):")
                            .formatted(Formatting.RED),
                    false
            );

            for (Problem problem : playerProblems) {
                String slot = problem.slot() > 0 ? "Slot " + problem.slot() : "Slot ?";
                String prefix = "  " + slot + " | " + problem.species() + " — ";
                String detail = problemDetail(problem);
                player.sendMessage(Text.literal(prefix + detail).formatted(Formatting.YELLOW), false);

                if (problem.kind() == ProblemKind.ABILITY) {
                    if (problem.allowedAbilities().size() == 1 && problem.slot() > 0) {
                        String commandAbility = toCommandId(problem.allowedAbilities().get(0));
                        player.sendMessage(
                                Text.literal(
                                        "    Correção exata: /pokemoneditother " + playerName + " " + problem.slot()
                                                + " ability=" + commandAbility
                                ).formatted(Formatting.GRAY),
                                false
                        );
                    } else if (problem.allowedAbilities().size() > 1) {
                        player.sendMessage(
                                Text.literal(
                                        "    Há mais de uma ability legal; escolha uma das opções acima. O ServerFixes NUNCA randomiza nem altera automaticamente."
                                ).formatted(Formatting.GRAY),
                                false
                        );
                    }
                }
            }
        }
    }

    private static void sendFullProblemReportToOnlineOperators(List<Problem> problems, MinecraftServer server) {
        if (server == null || problems == null || problems.isEmpty()) return;

        List<ServerPlayerEntity> operators = new ArrayList<>();
        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            if (server.getPlayerManager().isOperator(online.getGameProfile())) {
                operators.add(online);
            }
        }
        if (operators.isEmpty()) return;

        long reportNumber = operatorReports.incrementAndGet();
        for (ServerPlayerEntity operator : operators) {
            operator.sendMessage(
                    Text.literal(
                            "[CKT-GEN3-ADM] Relatório COMPLETO #" + reportNumber + " — PvP bloqueado por "
                                    + problems.size() + " incompatibilidade(s):"
                    ).formatted(Formatting.RED),
                    false
            );

            for (Problem problem : problems) {
                String slot = problem.slot() > 0 ? "Slot " + problem.slot() : "Slot ?";
                String player = problem.playerName() == null || problem.playerName().isBlank()
                        ? "Jogador"
                        : problem.playerName();
                operator.sendMessage(
                        Text.literal(
                                "  [" + player + "] " + slot + " | " + problem.species() + " — " + problemDetail(problem)
                        ).formatted(Formatting.YELLOW),
                        false
                );

                if (problem.kind() == ProblemKind.ABILITY
                        && problem.allowedAbilities().size() == 1
                        && problem.slot() > 0) {
                    operator.sendMessage(
                            Text.literal(
                                    "    Correção: /pokemoneditother " + player + " " + problem.slot()
                                            + " ability=" + toCommandId(problem.allowedAbilities().get(0))
                            ).formatted(Formatting.GRAY),
                            false
                    );
                }
            }
        }
    }

    private static void broadcastToOnlineOperators(MinecraftServer server, Text text) {
        if (server == null || text == null) return;
        boolean sent = false;
        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            if (!server.getPlayerManager().isOperator(online.getGameProfile())) continue;
            online.sendMessage(text, false);
            sent = true;
        }
        if (sent) operatorReports.incrementAndGet();
    }

    private static String problemDetail(Problem problem) {
        return switch (problem.kind()) {
            case ABILITY -> {
                String allowed = problem.allowedAbilities().isEmpty()
                        ? "nenhuma habilidade identificada"
                        : String.join(" / ", problem.allowedAbilities());
                yield "Ability " + problem.value() + " ❌ | Permitidas na Gen 3: " + allowed;
            }
            case MOVE -> "Golpe " + problem.value() + " ❌ | " + problem.reason();
            case ITEM -> "Held item " + problem.value() + " ❌ | " + problem.reason();
            case FORM -> "Forma " + problem.value() + " ❌ | " + problem.reason();
        };
    }

    private static boolean isPurePlayerPvp(Object battle) {
        try {
            Object actors = invokeNoArgs(battle, "getActors");
            if (!(actors instanceof Iterable<?> iterable)) return false;
            int count = 0;
            for (Object actor : iterable) {
                count++;
                if (!actorTypeIs(actor, "PLAYER")) return false;
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

    private static int resolvePartySlot(Object pokemon) {
        try {
            Object observable = invokeNoArgs(pokemon, "getStoreCoordinates");
            Object coordinates = invokeNoArgs(observable, "get");
            Object position = invokeNoArgs(coordinates, "getPosition");
            Object slot = invokeNoArgs(position, "getSlot");
            if (slot instanceof Number number) return number.intValue() + 1;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String playerName(UUID uuid, Object actor) {
        MinecraftServer server = TournamentBattleBridge.activeServerForInternalUse();
        if (server != null && uuid != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && player.getGameProfile().getName() != null) return player.getGameProfile().getName();
        }
        Object name = invokeNoArgsQuietly(actor, "getName");
        if (name != null) {
            Object string = invokeNoArgsQuietly(name, "getString");
            if (string != null && !string.toString().isBlank()) return string.toString();
        }
        return "Jogador";
    }

    private static void broadcastToBattlePlayers(Object battle, MinecraftServer server, Text text) {
        if (battle == null || server == null || text == null) return;
        Object actors = invokeNoArgsQuietly(battle, "getActors");
        if (!(actors instanceof Iterable<?> iterable)) return;
        Set<UUID> sent = new LinkedHashSet<>();
        for (Object actor : iterable) {
            if (!actorTypeIs(actor, "PLAYER")) continue;
            UUID uuid = asUuid(invokeNoArgsQuietly(actor, "getUuid"));
            if (uuid == null || !sent.add(uuid)) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) player.sendMessage(text, false);
        }
    }

    private static String simplifyMoveReason(String reason) {
        if (reason == null || reason.isBlank()) return "conteúdo do futuro (Gen 4+)";
        String cleaned = reason.replace("Pokémon", "Pokemon").trim();
        if (cleaned.length() > 220) cleaned = cleaned.substring(0, 217) + "...";
        return cleaned;
    }

    private static String toCommandId(String ability) {
        return ability == null ? "" : ability.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> lowerSet(JsonArray array) {
        Set<String> values = new LinkedHashSet<>();
        if (array != null) for (JsonElement element : array) values.add(element.getAsString().toLowerCase(Locale.ROOT));
        return values;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : null;
    }

    private static boolean boolOr(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String nullableString(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        if (target == null) throw new NullPointerException("target null for " + name);
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
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
        }
        return null;
    }

    public record EnableResult(boolean ok, String message) {
        static EnableResult ok(String message) { return new EnableResult(true, message); }
        static EnableResult fail(String message) { return new EnableResult(false, message); }
    }

    private record PokemonSnapshot(
            UUID playerUuid,
            String playerName,
            int partySlot,
            String speciesId,
            String formShowdownId,
            String formName,
            String abilityName,
            List<String> moves,
            int level,
            String heldItemShowdownId
    ) {
    }

    private record MoveProblem(String move, String reason) {
    }

    private record ValidationResult(
            boolean speciesExists,
            String speciesName,
            boolean abilityAllowed,
            String currentAbility,
            List<String> allowedAbilities,
            List<MoveProblem> illegalMoves,
            boolean itemAllowed,
            String currentItem,
            String itemReason,
            boolean formAllowed,
            String currentForm,
            String formReason
    ) {
    }

    private enum ProblemKind {
        ABILITY,
        MOVE,
        ITEM,
        FORM
    }

    private record Problem(
            ProblemKind kind,
            UUID playerUuid,
            String playerName,
            int slot,
            String species,
            String value,
            String reason,
            List<String> allowedAbilities
    ) {
        static Problem ability(UUID playerUuid, String player, int slot, String species, String ability, List<String> allowed) {
            return new Problem(ProblemKind.ABILITY, playerUuid, player, slot, species, ability, "", List.copyOf(allowed));
        }

        static Problem move(UUID playerUuid, String player, int slot, String species, String move, String reason) {
            return new Problem(ProblemKind.MOVE, playerUuid, player, slot, species, move, reason, List.of());
        }

        static Problem item(UUID playerUuid, String player, int slot, String species, String item, String reason) {
            return new Problem(ProblemKind.ITEM, playerUuid, player, slot, species, item, reason, List.of());
        }

        static Problem form(UUID playerUuid, String player, int slot, String species, String form, String reason) {
            return new Problem(ProblemKind.FORM, playerUuid, player, slot, species, form, reason, List.of());
        }
    }
}
