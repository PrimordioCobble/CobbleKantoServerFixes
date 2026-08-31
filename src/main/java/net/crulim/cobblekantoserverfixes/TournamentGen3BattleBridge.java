package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-only Gen 3 battle format override for every pure player-vs-player CobbleKanto battle.
 *
 * <p>This class deliberately avoids a compile-time Cobblemon dependency. A tiny optional mixin
 * intercepts the BattleFormat argument immediately before Cobblemon constructs PokemonBattle.
 * The tournament format uses both {@code gen=3} and the native Showdown {@code mod=gen3} Dex.
 * The Showdown fork still gives Cobblemon's global species registry precedence over historical
 * Pokedex entries, so synthetic Legacy forms must keep their own registered Showdown ids instead
 * of being collapsed back to modern base species. When the toggle is OFF, or if either side
 * contains a non-player actor, the original object is returned untouched. Singles, doubles,
 * triples and multi-battles are all eligible.</p>
 */
public final class TournamentGen3BattleBridge {
    private static final String BATTLE_FORMAT_CLASS = "com.cobblemon.mod.common.battles.BattleFormat";
    private static final String HISTORICAL_DEX_MOD = "gen3";

    private static volatile boolean enabled;
    private static volatile boolean reflectionReady;
    private static volatile String readiness = "não validado nesta inicialização";
    private static volatile String lastApplication = "nenhuma batalha Gen 3 aplicada nesta inicialização";

    private static volatile Method getMod;
    private static volatile Method getBattleType;
    private static volatile Method getRuleSet;
    private static volatile Method getGen;
    private static volatile Method getAdjustLevel;
    private static volatile Method copy;

    private static final AtomicLong rewrittenBattles = new AtomicLong();
    private static final AtomicLong verifiedBattles = new AtomicLong();
    private static final AtomicLong legacyAbilityFallbacks = new AtomicLong();
    private static final AtomicLong legacyShowdownIdFixes = new AtomicLong();

    private TournamentGen3BattleBridge() {
    }

    public static synchronized boolean enable() {
        if (!ensureReflectionReady()) {
            enabled = false;
            return false;
        }

        TournamentGen3ShowdownDiagnosticBridge.EngineReadiness engineReadiness =
                TournamentGen3ShowdownDiagnosticBridge.verifyHistoricalEngineReadiness();
        if (!engineReadiness.ok()) {
            enabled = false;
            readiness = "FAIL-ENGINE:" + engineReadiness.message();
            lastApplication = "FAIL-CLOSED: " + engineReadiness.message();
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3] Historical runtime self-test failed; Gen 3 override will stay OFF. {}",
                    engineReadiness.message()
            );
            return false;
        }

        enabled = true;
        lastApplication = "aguardando próximo PvP";
        CobbleKantoServerFixes.LOGGER.info(
                "[CKT-GEN3] Historical Gen 3 override enabled for every pure PvP battle (mod=gen3, gen=3). Wild/NPC/raid battles are untouched."
        );
        return true;
    }

    public static synchronized void disable(String reason) {
        boolean wasEnabled = enabled;
        enabled = false;
        if (wasEnabled) {
            CobbleKantoServerFixes.LOGGER.info("[CKT-GEN3] Historical Gen 3 override disabled ({}).", reason);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String statusSummary() {
        return (enabled ? "ON" : "OFF")
                + ",hook=" + (reflectionReady ? "READY" : readiness)
                + ",dexMod=" + HISTORICAL_DEX_MOD
                + ",applied=" + rewrittenBattles.get()
                + ",verified=" + verifiedBattles.get()
                + ",abilityFixes=" + legacyAbilityFallbacks.get()
                + ",legacyIdFixes=" + legacyShowdownIdFixes.get()
                + ",last=" + lastApplication;
    }

    /**
     * Records a compatibility repair performed on a legacy Showdown side request.
     * Showdown intentionally omits the current ability from pre-Gen-7 request JSON, while
     * Cobblemon 1.7.3 assumes that property is always initialized before packet encoding.
     */
    public static void noteLegacyAbilityFallback(String fallback) {
        long count = legacyAbilityFallbacks.incrementAndGet();
        if (count == 1L) {
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3] Legacy Showdown request omitted current ability; using baseAbility '{}' for client packet compatibility.",
                    fallback
            );
        }
    }

    /**
     * Preserves the synthetic "Legacy" form Showdown id used by the tournament typing datapack.
     * Cobblemon 1.7.3 registers non-standard forms in its Showdown species registry, including
     * their FormData typing. Mapping "azumarilllegacy" back to the base "azumarill" would make
     * Cobblemon's modern base species (Water/Fairy) win again inside the simulator.
     *
     * <p>The historical method name is retained to avoid touching the optional mixin contract, but
     * the correct behavior is now the opposite of the old normalization: while Gen 3 is ON, a
     * technical Legacy form is explicitly kept as its own form Showdown id.</p>
     *
     * @return the Legacy form Showdown id when one is present, otherwise null
     */
    public static String maybeNormalizeLegacyShowdownId(Object pokemon) {
        if (!enabled || pokemon == null) {
            return null;
        }
        try {
            Object form = pokemon.getClass().getMethod("getForm").invoke(pokemon);
            if (form == null) {
                return null;
            }
            Object name = form.getClass().getMethod("getName").invoke(form);
            if (name == null || !"legacy".equalsIgnoreCase(name.toString())) {
                return null;
            }

            Object showdownId = form.getClass().getMethod("showdownId").invoke(form);
            if (showdownId == null || showdownId.toString().isBlank()) {
                return null;
            }

            long count = legacyShowdownIdFixes.incrementAndGet();
            if (count == 1L) {
                CobbleKantoServerFixes.LOGGER.info(
                        "[CKT-GEN3] Preserving synthetic Legacy form Showdown ids so datapack historical typing reaches the Gen 3 simulator."
                );
            }
            return showdownId.toString();
        } catch (Throwable throwable) {
            // Never break Pokemon serialization because the optional compatibility probe failed.
            CobbleKantoServerFixes.LOGGER.warn(
                    "[CKT-GEN3] Could not preserve a possible Legacy form Showdown id; leaving Cobblemon behavior untouched.",
                    throwable
            );
            return null;
        }
    }

    /**
     * Called from the optional BattleRegistry mixin. Never throws into Cobblemon's battle path.
     */
    public static Object maybeRewriteFormat(Object originalFormat, Object side1, Object side2) {
        if (!enabled || originalFormat == null || side1 == null || side2 == null) {
            return originalFormat;
        }

        try {
            if (!ensureReflectionReady()) {
                failClosed("reflection became unavailable while applying the override", null);
                return originalFormat;
            }
            if (!isPlayerOnlySide(side1) || !isPlayerOnlySide(side2)) {
                return originalFormat;
            }

            int currentGen = ((Number) getGen.invoke(originalFormat)).intValue();
            Object currentModValue = getMod.invoke(originalFormat);
            String currentMod = currentModValue == null ? "" : currentModValue.toString();

            if (currentGen == 3 && HISTORICAL_DEX_MOD.equalsIgnoreCase(currentMod)) {
                lastApplication = "PvP já estava em mod=gen3/gen=3";
                return originalFormat;
            }

            Object rewritten = copy.invoke(
                    originalFormat,
                    HISTORICAL_DEX_MOD,
                    getBattleType.invoke(originalFormat),
                    getRuleSet.invoke(originalFormat),
                    3,
                    ((Number) getAdjustLevel.invoke(originalFormat)).intValue()
            );

            long count = rewrittenBattles.incrementAndGet();
            lastApplication = "PvP reescrito de mod=" + currentMod + "/Gen " + currentGen
                    + " para mod=gen3/Gen 3 (#" + count + ")";
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3] Rewrote pure PvP BattleFormat from mod={}, gen={} to mod=gen3, gen=3 (application #{}).",
                    currentMod,
                    currentGen,
                    count
            );
            return rewritten;
        } catch (Throwable throwable) {
            failClosed("erro ao reescrever BattleFormat", throwable);
            return originalFormat;
        }
    }


    /**
     * Post-start verification used by the existing Cobblemon BATTLE_STARTED_POST subscription.
     * This is intentionally independent from Challonge state so operators can test Gen 3 locally
     * before turning tournament synchronization on.
     */
    public static void observeStartedBattle(Object battle) {
        if (!enabled || battle == null) {
            return;
        }
        try {
            Object format = battle.getClass().getMethod("getFormat").invoke(battle);
            Object side1 = battle.getClass().getMethod("getSide1").invoke(battle);
            Object side2 = battle.getClass().getMethod("getSide2").invoke(battle);
            if (format == null || !isPlayerOnlySide(side1) || !isPlayerOnlySide(side2)) {
                return;
            }
            int gen = ((Number) getGen.invoke(format)).intValue();
            Object modValue = getMod.invoke(format);
            String mod = modValue == null ? "" : modValue.toString();
            if (gen != 3 || !HISTORICAL_DEX_MOD.equalsIgnoreCase(mod)) {
                failClosed(
                        "verificação pós-start encontrou PvP em mod=" + mod + "/Gen " + gen
                                + " (esperado mod=gen3/Gen 3)",
                        null
                );
                return;
            }
            long count = verifiedBattles.incrementAndGet();
            lastApplication = "VERIFIED: PvP iniciou em mod=gen3/Gen 3 (#" + count + ")";
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT-GEN3] Post-start verification OK: pure PvP is running with mod=gen3, gen=3 (verification #{}).",
                    count
            );
        } catch (Throwable throwable) {
            failClosed("erro na verificação pós-start do BattleFormat", throwable);
        }
    }

    private static synchronized boolean ensureReflectionReady() {
        if (reflectionReady) {
            return true;
        }

        try {
            Class<?> formatClass = Class.forName(BATTLE_FORMAT_CLASS, false, TournamentGen3BattleBridge.class.getClassLoader());
            getMod = formatClass.getMethod("getMod");
            getBattleType = formatClass.getMethod("getBattleType");
            getRuleSet = formatClass.getMethod("getRuleSet");
            getGen = formatClass.getMethod("getGen");
            getAdjustLevel = formatClass.getMethod("getAdjustLevel");

            Method candidateCopy = null;
            for (Method method : formatClass.getMethods()) {
                if (!method.getName().equals("copy") || method.getParameterCount() != 5) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params[0] == String.class
                        && Set.class.isAssignableFrom(params[2])
                        && params[3] == int.class
                        && params[4] == int.class) {
                    candidateCopy = method;
                    break;
                }
            }
            if (candidateCopy == null) {
                throw new NoSuchMethodException("BattleFormat.copy(String, BattleType, Set, int, int)");
            }
            copy = candidateCopy;

            reflectionReady = true;
            readiness = "READY";
            return true;
        } catch (Throwable throwable) {
            reflectionReady = false;
            readiness = "FAIL:" + throwable.getClass().getSimpleName();
            CobbleKantoServerFixes.LOGGER.error(
                    "[CKT-GEN3] Could not validate Cobblemon 1.7.3 BattleFormat reflection. Gen 3 override will stay OFF.",
                    throwable
            );
            return false;
        }
    }

    /**
     * True only when the side contains at least one actor and every actor is a player.
     *
     * <p>This intentionally does not inspect BattleType. A doubles/triples battle can still be
     * one player per side, while Cobblemon multi-battles can have multiple player actors on each
     * side. The actor composition is the reliable PvP/PvE boundary.</p>
     */
    private static boolean isPlayerOnlySide(Object side) throws Exception {
        Method actorsGetter = side.getClass().getMethod("getActors");
        Object actors = actorsGetter.invoke(side);
        if (actors == null || !actors.getClass().isArray()) {
            return false;
        }

        int count = Array.getLength(actors);
        if (count < 1) {
            return false;
        }

        for (int index = 0; index < count; index++) {
            Object actor = Array.get(actors, index);
            if (actor == null) {
                return false;
            }
            Method typeGetter = actor.getClass().getMethod("getType");
            Object actorType = typeGetter.invoke(actor);
            if (actorType == null || !"PLAYER".equals(actorType.toString().toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static synchronized void failClosed(String message, Throwable throwable) {
        enabled = false;
        readiness = "FAIL-RUNTIME";
        lastApplication = "FAIL-CLOSED: " + message;
        if (throwable == null) {
            CobbleKantoServerFixes.LOGGER.error("[CKT-GEN3] {}. Override disabled fail-closed.", message);
        } else {
            CobbleKantoServerFixes.LOGGER.error("[CKT-GEN3] {}. Override disabled fail-closed.", message, throwable);
        }
    }
}
