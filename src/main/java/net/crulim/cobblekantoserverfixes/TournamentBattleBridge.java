package net.crulim.cobblekantoserverfixes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Temporary, operator-controlled CobbleKanto tournament automation.
 *
 * Safety properties:
 * - Runtime always starts OFF after a server boot.
 * - /cktournament on validates Challonge before becoming active.
 * - Cobblemon is accessed through reflection, preserving ServerFixes' optional dependency model.
 * - Every HTTP call runs on daemon background threads, never Minecraft's server thread.
 * - A PvP result that has no exact open Challonge match is ignored, never guessed.
 * - A battle UUID is processed at most once.
 */
public final class TournamentBattleBridge {
    private static final String COBBLEMON_EVENTS_CLASS = "com.cobblemon.mod.common.api.events.CobblemonEvents";
    private static final long CACHE_RETENTION_MILLIS = 2L * 60L * 60L * 1000L;

    private static final Map<UUID, BattlePair> ACTIVE_BATTLES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PROCESSED_BATTLES = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
    // Visual rendering/upload is intentionally isolated from Challonge API result reporting.
    // A slow/broken SVG renderer must never queue or block reportWinner()/participant updates.
    private static final ExecutorService DISPLAY_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cks-bracket-display");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, throwable) ->
                CobbleKantoServerFixes.LOGGER.error("Unhandled bracket display worker error.", throwable));
        return thread;
    });

    private static volatile RuntimeState state = RuntimeState.OFF;
    private static volatile MinecraftServer activeServer;
    private static volatile ChallongeTournamentClient client;
    private static volatile String lastSyncStatus = "Nenhum resultado processado nesta inicialização.";
    private static volatile String lastDisplayStatus = "Display não configurado nesta inicialização.";
    private static volatile ChallongeTournamentClient.Validation validation;
    private static volatile boolean battleEventsRegistered;
    private static volatile boolean legalityPreEventRegistered;
    private static boolean registered;

    private TournamentBattleBridge() {
    }

    static MinecraftServer activeServerForInternalUse() {
        return activeServer;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            TournamentConfig.reload();
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Could not initialize tournament config at {}. /cktournament on will retry loading it.",
                    TournamentConfig.path(),
                    exception
            );
        }

        CommandRegistrationCallback.EVENT.register(TournamentBattleBridge::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            forceOff("server-start");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> forceOff("server-stop"));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> activeServer = null);

        if (!FabricLoader.getInstance().isModLoaded("cobblemon")) {
            CobbleKantoServerFixes.LOGGER.warn(
                    "Cobblemon is not loaded; /cktournament is available but battle events cannot be monitored."
            );
            return;
        }

        // Legality PRE hook is isolated from the existing tournament result listeners.
        // If Cobblemon changes this cancelable event API, Challonge/result sync must still remain usable.
        try {
            subscribe("BATTLE_STARTED_PRE", TournamentBattleBridge::onBattleStartedPre);
            legalityPreEventRegistered = true;
            CobbleKantoServerFixes.LOGGER.info(
                    "Tournament Gen 3 legality PRE hook registered. Ability/move/item/form locks remain OFF until explicitly enabled."
            );
        } catch (Throwable throwable) {
            legalityPreEventRegistered = false;
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to register BATTLE_STARTED_PRE. Gen 3 legality locks will refuse activation, but tournament result sync remains available.",
                    throwable
            );
        }

        try {
            subscribe("BATTLE_STARTED_POST", TournamentBattleBridge::onBattleStarted);
            subscribe("BATTLE_VICTORY", TournamentBattleBridge::onBattleVictory);
            battleEventsRegistered = true;
            CobbleKantoServerFixes.LOGGER.info(
                    "Tournament battle bridge registered. Runtime state is OFF; use /cktournament on explicitly for an event."
            );
        } catch (Throwable throwable) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to register Cobblemon tournament battle events; /cktournament will remain unusable.",
                    throwable
            );
        }
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("cktournament")
                .requires(source -> source.hasPermissionLevel(TournamentConfig.current().commandPermissionLevel()))
                .then(CommandManager.literal("on")
                        .executes(context -> turnOn(context.getSource())))
                .then(CommandManager.literal("off")
                        .executes(context -> turnOff(context.getSource())))
                .then(CommandManager.literal("status")
                        .executes(context -> sendStatus(context.getSource())))
                .then(CommandManager.literal("gen3")
                        .then(CommandManager.literal("on")
                                .executes(context -> turnGen3On(context.getSource())))
                        .then(CommandManager.literal("off")
                                .executes(context -> turnGen3Off(context.getSource())))
                        .then(CommandManager.literal("status")
                                .executes(context -> sendGen3Status(context.getSource())))
                        .then(CommandManager.literal("abilitylock")
                                .then(CommandManager.literal("on")
                                        .executes(context -> turnAbilityLockOn(context.getSource())))
                                .then(CommandManager.literal("off")
                                        .executes(context -> turnAbilityLockOff(context.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(context -> sendAbilityLockStatus(context.getSource()))))
                        .then(CommandManager.literal("movelock")
                                .then(CommandManager.literal("on")
                                        .executes(context -> turnMoveLockOn(context.getSource())))
                                .then(CommandManager.literal("off")
                                        .executes(context -> turnMoveLockOff(context.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(context -> sendMoveLockStatus(context.getSource()))))
                        .then(CommandManager.literal("itemlock")
                                .then(CommandManager.literal("on")
                                        .executes(context -> turnItemLockOn(context.getSource())))
                                .then(CommandManager.literal("off")
                                        .executes(context -> turnItemLockOff(context.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(context -> sendItemLockStatus(context.getSource()))))
                        .then(CommandManager.literal("formlock")
                                .then(CommandManager.literal("on")
                                        .executes(context -> turnFormLockOn(context.getSource())))
                                .then(CommandManager.literal("off")
                                        .executes(context -> turnFormLockOff(context.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(context -> sendFormLockStatus(context.getSource())))))
                .then(CommandManager.literal("display")
                        .executes(context -> refreshDisplayCommand(context.getSource())))
                .then(CommandManager.literal("substitute")
                        .then(CommandManager.argument("participant", StringArgumentType.string())
                                .then(CommandManager.argument("newPlayer", EntityArgumentType.player())
                                        .executes(context -> substituteParticipant(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "participant"),
                                                EntityArgumentType.getPlayer(context, "newPlayer")
                                        )))))
        );
    }

    private static int turnOn(ServerCommandSource source) {
        if (!ServerFixesConfig.enabled) {
            source.sendError(Text.literal("CobbleKanto Server Fixes está desativado."));
            return 0;
        }
        if (!FabricLoader.getInstance().isModLoaded("cobblemon")) {
            source.sendError(Text.literal("Cobblemon não está carregado neste servidor."));
            return 0;
        }
        if (!battleEventsRegistered) {
            source.sendError(Text.literal("[CKT] Eventos de batalha do Cobblemon não foram registrados. O modo campeonato ficou fail-closed; veja o console."));
            return 0;
        }
        if (state == RuntimeState.ON) {
            source.sendFeedback(() -> Text.literal("[CKT] O modo campeonato já está ON."), false);
            return 1;
        }
        if (state == RuntimeState.STARTING) {
            source.sendFeedback(() -> Text.literal("[CKT] A integração já está sendo validada com o Challonge."), false);
            return 1;
        }

        final TournamentConfig.Snapshot config;
        try {
            config = TournamentConfig.reload();
        } catch (Exception exception) {
            source.sendError(Text.literal("[CKT] Não foi possível ler " + TournamentConfig.path() + ". Veja o console."));
            CobbleKantoServerFixes.LOGGER.error("Failed to reload tournament config.", exception);
            return 0;
        }

        if (!config.hasCredentials()) {
            source.sendError(Text.literal(
                    "[CKT] Configure challongeApiKey em " + TournamentConfig.path() + " e rode /cktournament on novamente."
            ));
            return 0;
        }

        final ChallongeTournamentClient candidate;
        try {
            candidate = new ChallongeTournamentClient(config);
        } catch (RuntimeException exception) {
            source.sendError(Text.literal("[CKT] Configuração do Challonge inválida: " + safeMessage(exception)));
            return 0;
        }

        long generation = GENERATION.incrementAndGet();
        state = RuntimeState.STARTING;
        client = null;
        validation = null;
        ACTIVE_BATTLES.clear();
        PROCESSED_BATTLES.clear();
        lastSyncStatus = "Validando conexão com o Challonge...";

        source.sendFeedback(() -> Text.literal(
                "[CKT] Validando Challonge '" + config.challongeTournament() + "' em segundo plano..."
        ), false);

        MinecraftServer server = source.getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                return candidate.validateAndLoad();
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }, HTTP_EXECUTOR).whenComplete((result, throwable) -> server.execute(() -> {
            if (GENERATION.get() != generation || state != RuntimeState.STARTING) {
                return;
            }

            Throwable cause = unwrap(throwable);
            if (cause != null) {
                state = RuntimeState.OFF;
                client = null;
                validation = null;
                lastSyncStatus = "Falha ao ativar: " + safeMessage(cause);
                source.sendError(Text.literal("[CKT] Não ligou. Challonge respondeu com erro: " + safeMessage(cause)));
                CobbleKantoServerFixes.LOGGER.error(
                        "Tournament mode validation failed for Challonge tournament {}.",
                        config.challongeTournament(),
                        cause
                );
                return;
            }

            if (result.participantCount() < 2) {
                state = RuntimeState.OFF;
                client = null;
                validation = null;
                lastSyncStatus = "Falha ao ativar: menos de 2 participantes no Challonge.";
                source.sendError(Text.literal("[CKT] Não ligou: a chave retornou menos de 2 participantes."));
                return;
            }
            if (result.openMatchCount() < 1) {
                state = RuntimeState.OFF;
                client = null;
                validation = null;
                lastSyncStatus = "Falha ao ativar: nenhuma partida aberta no Challonge.";
                source.sendError(Text.literal(
                        "[CKT] Não ligou: não há partidas abertas. Inicie a chave no Challonge e rode /cktournament on novamente."
                ));
                return;
            }

            client = candidate;
            validation = result;
            state = RuntimeState.ON;
            lastSyncStatus = "Integração validada; aguardando resultados.";

            String type = result.tournamentType().isBlank() ? "tipo desconhecido" : result.tournamentType();
            source.sendFeedback(() -> Text.literal(
                    "[CKT] ON — " + fallback(result.tournamentName(), config.challongeTournament())
                            + " | " + result.participantCount() + " participantes"
                            + " | " + result.openMatchCount() + " partidas abertas"
                            + " | " + type
            ).formatted(Formatting.GREEN), true);

            CobbleKantoServerFixes.LOGGER.info(
                    "Tournament mode ON. tournament={}, name={}, type={}, participants={}, openMatches={}",
                    config.challongeTournament(),
                    result.tournamentName(),
                    result.tournamentType(),
                    result.participantCount(),
                    result.openMatchCount()
            );

            if (config.hasBracketDisplayConfig()) {
                triggerDisplayRefresh(null, "tournament-enable");
            }
        }));
        return 1;
    }

    private static int turnOff(ServerCommandSource source) {
        boolean wasActive = state != RuntimeState.OFF;
        boolean gen3WasActive = TournamentGen3BattleBridge.isEnabled();
        forceOff("operator-command");
        source.sendFeedback(() -> Text.literal(
                (wasActive || gen3WasActive)
                        ? "[CKT] OFF — Challonge e regras Gen 3 de PvP foram desativados."
                        : "[CKT] O modo campeonato já estava completamente OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int turnGen3On(ServerCommandSource source) {
        if (!ServerFixesConfig.enabled) {
            source.sendError(Text.literal("CobbleKanto Server Fixes está desativado."));
            return 0;
        }
        if (!FabricLoader.getInstance().isModLoaded("cobblemon")) {
            source.sendError(Text.literal("Cobblemon não está carregado neste servidor."));
            return 0;
        }
        if (TournamentGen3BattleBridge.isEnabled()) {
            source.sendFeedback(() -> Text.literal(
                    "[CKT-GEN3] Já está ON — todo PvP entre players já usa regras mecânicas da Gen 3."
            ).formatted(Formatting.YELLOW), false);
            return 1;
        }
        if (!TournamentGen3BattleBridge.enable()) {
            source.sendError(Text.literal(
                    "[CKT-GEN3] Self-test histórico falhou (BattleFormat/Dex/Steel/split/Legacy). Ficou OFF; veja [CKT-GEN3-GUARD] no console."
            ));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] ON — runtime histórico validado: Gen 3 + Steel pré-Gen6 + split por tipo + Azumarill Legacy=Water. Todo PvP entre players usará Gen 3. Wild/NPC/raid permanecem normais. Locks continuam OFF até você ligá-los separadamente."
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int turnGen3Off(ServerCommandSource source) {
        boolean wasEnabled = TournamentGen3BattleBridge.isEnabled();
        boolean locksWereEnabled = TournamentGen3LegalityBridge.isAbilityLockEnabled()
                || TournamentGen3LegalityBridge.isMoveLockEnabled()
                || TournamentGen3LegalityBridge.isItemLockEnabled()
                || TournamentGen3LegalityBridge.isFormLockEnabled();
        TournamentGen3LegalityBridge.disableAll("operator-gen3-command");
        TournamentGen3BattleBridge.disable("operator-gen3-command");
        source.sendFeedback(() -> Text.literal(
                wasEnabled || locksWereEnabled
                        ? "[CKT-GEN3] OFF — novos PvPs voltam ao BattleFormat normal; AbilityLock, MoveLock, ItemLock e FormLock também foram desligados."
                        : "[CKT-GEN3] Já estava OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int turnAbilityLockOn(ServerCommandSource source) {
        if (!legalityPreEventRegistered) {
            source.sendError(Text.literal(
                    "[CKT-GEN3] AbilityLock NÃO ligou: o hook BATTLE_STARTED_PRE não foi registrado. Veja o console; a batalha continua liberada."
            ));
            return 0;
        }
        TournamentGen3LegalityBridge.EnableResult result = TournamentGen3LegalityBridge.enableAbilityLock();
        if (!result.ok()) {
            source.sendError(Text.literal("[CKT-GEN3] AbilityLock NÃO ligou: " + result.message()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] AbilityLock ON — habilidades ilegais para a Gen 3 bloquearão o PvP antes de iniciar, com diagnóstico exato. Outros locks não foram alterados."
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int turnAbilityLockOff(ServerCommandSource source) {
        boolean wasEnabled = TournamentGen3LegalityBridge.isAbilityLockEnabled();
        TournamentGen3LegalityBridge.disableAbilityLock("operator-command");
        source.sendFeedback(() -> Text.literal(
                wasEnabled
                        ? "[CKT-GEN3] AbilityLock OFF — habilidades não bloquearão novas batalhas."
                        : "[CKT-GEN3] AbilityLock já estava OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int sendAbilityLockStatus(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] AbilityLock=" + (TournamentGen3LegalityBridge.isAbilityLockEnabled() ? "ON" : "OFF")
                        + " | preHook=" + (legalityPreEventRegistered ? "READY" : "FAIL")
                        + " | " + TournamentGen3LegalityBridge.statusSummary()
        ), false);
        return 1;
    }

    private static int turnMoveLockOn(ServerCommandSource source) {
        if (!legalityPreEventRegistered) {
            source.sendError(Text.literal(
                    "[CKT-GEN3] MoveLock NÃO ligou: o hook BATTLE_STARTED_PRE não foi registrado. Veja o console; a batalha continua liberada."
            ));
            return 0;
        }
        TournamentGen3LegalityBridge.EnableResult result = TournamentGen3LegalityBridge.enableMoveLock();
        if (!result.ok()) {
            source.sendError(Text.literal("[CKT-GEN3] MoveLock NÃO ligou: " + result.message()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] MoveLock ON — FUTURE-ONLY: golpes comprovadamente Gen 4+ bloqueiam; Gen 1/2/3 passam sem checagem de learnset/procedência. Outros locks não foram alterados."
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int turnMoveLockOff(ServerCommandSource source) {
        boolean wasEnabled = TournamentGen3LegalityBridge.isMoveLockEnabled();
        TournamentGen3LegalityBridge.disableMoveLock("operator-command");
        source.sendFeedback(() -> Text.literal(
                wasEnabled
                        ? "[CKT-GEN3] MoveLock OFF — golpes não bloquearão novas batalhas."
                        : "[CKT-GEN3] MoveLock já estava OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int sendMoveLockStatus(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] MoveLock=" + (TournamentGen3LegalityBridge.isMoveLockEnabled() ? "ON" : "OFF")
                        + " | preHook=" + (legalityPreEventRegistered ? "READY" : "FAIL")
                        + " | " + TournamentGen3LegalityBridge.statusSummary()
        ), false);
        return 1;
    }

    private static int turnItemLockOn(ServerCommandSource source) {
        if (!legalityPreEventRegistered) {
            source.sendError(Text.literal(
                    "[CKT-GEN3] ItemLock NÃO ligou: o hook BATTLE_STARTED_PRE não foi registrado. Veja o console; a batalha continua liberada."
            ));
            return 0;
        }
        TournamentGen3LegalityBridge.EnableResult result = TournamentGen3LegalityBridge.enableItemLock();
        if (!result.ok()) {
            source.sendError(Text.literal("[CKT-GEN3] ItemLock NÃO ligou: " + result.message()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] ItemLock ON — FUTURE-ONLY: held items comprovadamente Gen 4+ bloqueiam; itens Gen 1/2/3 e ids custom/desconhecidos não são barrados por este lock. Outros locks não foram alterados."
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int turnItemLockOff(ServerCommandSource source) {
        boolean wasEnabled = TournamentGen3LegalityBridge.isItemLockEnabled();
        TournamentGen3LegalityBridge.disableItemLock("operator-command");
        source.sendFeedback(() -> Text.literal(
                wasEnabled
                        ? "[CKT-GEN3] ItemLock OFF — held items não bloquearão novas batalhas."
                        : "[CKT-GEN3] ItemLock já estava OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int sendItemLockStatus(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] ItemLock=" + (TournamentGen3LegalityBridge.isItemLockEnabled() ? "ON" : "OFF")
                        + " | preHook=" + (legalityPreEventRegistered ? "READY" : "FAIL")
                        + " | " + TournamentGen3LegalityBridge.statusSummary()
        ), false);
        return 1;
    }

    private static int turnFormLockOn(ServerCommandSource source) {
        if (!legalityPreEventRegistered) {
            source.sendError(Text.literal(
                    "[CKT-GEN3] FormLock NÃO ligou: o hook BATTLE_STARTED_PRE não foi registrado. Veja o console; a batalha continua liberada."
            ));
            return 0;
        }
        TournamentGen3LegalityBridge.EnableResult result = TournamentGen3LegalityBridge.enableFormLock();
        if (!result.ok()) {
            source.sendError(Text.literal("[CKT-GEN3] FormLock NÃO ligou: " + result.message()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] FormLock ON — formas posteriores à Gen 3 bloquearão o PvP. A forma Legacy usada pelo datapack de tipagem é aceita. Outros locks não foram alterados."
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int turnFormLockOff(ServerCommandSource source) {
        boolean wasEnabled = TournamentGen3LegalityBridge.isFormLockEnabled();
        TournamentGen3LegalityBridge.disableFormLock("operator-command");
        source.sendFeedback(() -> Text.literal(
                wasEnabled
                        ? "[CKT-GEN3] FormLock OFF — formas não bloquearão novas batalhas."
                        : "[CKT-GEN3] FormLock já estava OFF."
        ).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int sendFormLockStatus(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] FormLock=" + (TournamentGen3LegalityBridge.isFormLockEnabled() ? "ON" : "OFF")
                        + " | preHook=" + (legalityPreEventRegistered ? "READY" : "FAIL")
                        + " | " + TournamentGen3LegalityBridge.statusSummary()
        ), false);
        return 1;
    }

    private static int sendGen3Status(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
                "[CKT-GEN3] " + TournamentGen3BattleBridge.statusSummary()
                        + " | preHook=" + (legalityPreEventRegistered ? "READY" : "FAIL")
                        + " | dexDiag=" + TournamentGen3ShowdownDiagnosticBridge.statusSummary()
                        + " | legality=" + TournamentGen3LegalityBridge.statusSummary()
        ), false);
        return 1;
    }

    private static int sendStatus(ServerCommandSource source) {
        TournamentConfig.Snapshot config = TournamentConfig.current();
        ChallongeTournamentClient.Validation currentValidation = validation;
        StringBuilder status = new StringBuilder("[CKT] ").append(state)
                .append(" | events=").append(battleEventsRegistered ? "OK" : "FAIL")
                .append(" | legalityPre=").append(legalityPreEventRegistered ? "OK" : "FAIL")
                .append(" | tournament=").append(config.challongeTournament())
                .append(" | apiKey=").append(config.challongeApiKey().isBlank() ? "NÃO CONFIGURADA" : "configurada");
        if (currentValidation != null) {
            status.append(" | participants=").append(currentValidation.participantCount())
                    .append(" | openAtEnable=").append(currentValidation.openMatchCount());
        }
        status.append(" | gen3=").append(TournamentGen3BattleBridge.statusSummary())
                .append(" | dexDiag=").append(TournamentGen3ShowdownDiagnosticBridge.statusSummary())
                .append(" | legality=").append(TournamentGen3LegalityBridge.statusSummary())
                .append(" | last=").append(lastSyncStatus)
                .append(" | display=").append(lastDisplayStatus);
        source.sendFeedback(() -> Text.literal(status.toString()), false);
        return 1;
    }

    private static void forceOff(String reason) {
        TournamentGen3LegalityBridge.disableAll(reason);
        TournamentGen3BattleBridge.disable(reason);
        GENERATION.incrementAndGet();
        state = RuntimeState.OFF;
        client = null;
        validation = null;
        ACTIVE_BATTLES.clear();
        PROCESSED_BATTLES.clear();
        lastSyncStatus = "OFF (" + reason + ")";
        lastDisplayStatus = "OFF (" + reason + ")";
    }

    private static void onBattleStartedPre(Object event) {
        // Read-only diagnostic runs before Cobblemon starts the Showdown stream. It must never
        // cancel a battle or change the tournament engine; any probe failure is isolated here.
        TournamentGen3ShowdownDiagnosticBridge.inspectBeforeShowdown(event);
        TournamentGen3LegalityBridge.inspectAndMaybeCancel(event, activeServer);
    }

    private static void onBattleStarted(Object event) {
        Object battle = invokeNoArgsQuietly(event, "getBattle");
        TournamentGen3BattleBridge.observeStartedBattle(battle);
        if (state != RuntimeState.ON || activeServer == null) {
            return;
        }
        UUID battleId = battleId(battle);
        if (battleId == null) {
            return;
        }

        List<PlayerIdentity> players = playerIdentitiesFromBattle(battle);
        if (players.size() != 2) {
            return;
        }
        players = players.stream()
                .sorted(Comparator.comparing(identity -> identity.uuid().toString()))
                .toList();
        ACTIVE_BATTLES.put(battleId, new BattlePair(players.get(0), players.get(1), System.currentTimeMillis()));
        pruneCaches();
    }

    private static void onBattleVictory(Object event) {
        if (state != RuntimeState.ON || activeServer == null) {
            return;
        }

        Object battle = invokeNoArgsQuietly(event, "getBattle");
        UUID battleId = battleId(battle);
        if (battleId == null || !markBattleOnce(battleId)) {
            return;
        }

        BattlePair cached = ACTIVE_BATTLES.remove(battleId);
        List<PlayerIdentity> winners = playerIdentitiesFromActors(invokeNoArgsQuietly(event, "getWinners"), cached);
        List<PlayerIdentity> losers = playerIdentitiesFromActors(invokeNoArgsQuietly(event, "getLosers"), cached);

        if (winners.size() != 1 || losers.size() != 1 || winners.get(0).uuid().equals(losers.get(0).uuid())) {
            if (TournamentConfig.current().logIgnoredResults()) {
                CobbleKantoServerFixes.LOGGER.info(
                        "[CKT] Ignored battle {} because it was not a simple 1-player-vs-1-player PvP result (winners={}, losers={}).",
                        battleId,
                        winners.size(),
                        losers.size()
                );
            }
            lastSyncStatus = "Ignorado: batalha " + battleId + " não era PvP 1x1 entre jogadores.";
            return;
        }

        PlayerIdentity winner = refreshIdentity(winners.get(0));
        PlayerIdentity loser = refreshIdentity(losers.get(0));

        ChallongeTournamentClient currentClient = client;
        if (currentClient == null || state != RuntimeState.ON) {
            lastSyncStatus = "Vitória detectada, mas cliente Challonge não estava disponível.";
            return;
        }

        String winnerRealName = winner.realName();
        String loserRealName = loser.realName();
        String winnerDisplayName = displayName(winner);
        String loserDisplayName = displayName(loser);
        if ((!winnerDisplayName.isBlank() && !winnerDisplayName.equalsIgnoreCase(winnerRealName))
                || (!loserDisplayName.isBlank() && !loserDisplayName.equalsIgnoreCase(loserRealName))) {
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT] Alias-safe match sync: battle={} winnerAccount='{}' winnerDisplay='{}' loserAccount='{}' loserDisplay='{}'.",
                    battleId, winnerRealName, winnerDisplayName, loserRealName, loserDisplayName
            );
        }
        if (winnerRealName.isBlank() || loserRealName.isBlank()) {
            lastSyncStatus = "Vitória detectada, mas faltou username real para sincronizar.";
            CobbleKantoServerFixes.LOGGER.warn(
                    "[CKT] Battle {} could not be synced because a real Minecraft username was unavailable (winner='{}', loser='{}').",
                    battleId,
                    winnerRealName,
                    loserRealName
            );
            return;
        }

        long generation = GENERATION.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                return currentClient.reportWinner(winnerRealName, loserRealName);
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }, HTTP_EXECUTOR).whenComplete((result, throwable) -> {
            MinecraftServer server = activeServer;
            if (server == null) {
                return;
            }
            server.execute(() -> {
                if (GENERATION.get() != generation || state != RuntimeState.ON) {
                    return;
                }

                Throwable cause = unwrap(throwable);
                if (cause != null) {
                    lastSyncStatus = "ERRO: " + winnerRealName + " > " + loserRealName + " — " + safeMessage(cause);
                    CobbleKantoServerFixes.LOGGER.error(
                            "[CKT] Victory detected but Challonge sync failed for battle {} ({} > {}).",
                            battleId,
                            winnerRealName,
                            loserRealName,
                            cause
                    );
                    return;
                }

                if (result.status() == ChallongeTournamentClient.Status.UPDATED) {
                    String matchLabel = result.matchIdentifier().isBlank()
                            ? result.matchId()
                            : result.matchIdentifier() + "/" + result.matchId();
                    lastSyncStatus = "OK: " + winnerRealName + " > " + loserRealName + " | match=" + matchLabel;
                    CobbleKantoServerFixes.LOGGER.info(
                            "[CKT] Challonge updated: battle={} match={} winner={} loser={}",
                            battleId,
                            matchLabel,
                            winnerRealName,
                            loserRealName
                    );

                    // Only official Challonge matches are announced. Unrelated PvP stays silent.
                    if (TournamentConfig.current().announceVictories()) {
                        broadcastVictory(winner, loser);
                    }
                    triggerDisplayRefresh(null, "match-" + matchLabel);
                } else {
                    lastSyncStatus = "IGNORADO: " + winnerRealName + " > " + loserRealName + " | " + result.reason();
                    if (TournamentConfig.current().logIgnoredResults()) {
                        CobbleKantoServerFixes.LOGGER.info(
                                "[CKT] Result ignored safely: battle={} winner={} loser={} reason={} detail={}",
                                battleId,
                                winnerRealName,
                                loserRealName,
                                result.reason(),
                                result.detail()
                        );
                    }
                }
            });
        });
    }

    private static int refreshDisplayCommand(ServerCommandSource source) {
        if (state != RuntimeState.ON || client == null) {
            source.sendError(Text.literal("[CKT] Ligue o modo campeonato antes de atualizar o display."));
            return 0;
        }

        final TournamentConfig.Snapshot config;
        try {
            config = TournamentConfig.reload();
        } catch (Exception exception) {
            source.sendError(Text.literal("[CKT] Não foi possível reler " + TournamentConfig.path() + "."));
            CobbleKantoServerFixes.LOGGER.error("Failed to reload tournament config for display refresh.", exception);
            return 0;
        }
        if (!config.hasBracketDisplayConfig()) {
            source.sendError(Text.literal(
                    "[CKT] Display desativado/incompleto. Configure bracketDisplayEnabled, imgbbApiKey e waterframesUpdateCommand."
            ));
            return 0;
        }

        triggerDisplayRefresh(source, "operator-command");
        return 1;
    }

    private static int substituteParticipant(
            ServerCommandSource source,
            String currentParticipant,
            ServerPlayerEntity replacementPlayer
    ) {
        ChallongeTournamentClient currentClient = client;
        if (state != RuntimeState.ON || currentClient == null) {
            source.sendError(Text.literal("[CKT] Ligue o modo campeonato antes de substituir um participante."));
            return 0;
        }

        String currentName = currentParticipant == null ? "" : currentParticipant.trim();
        String replacementName = replacementPlayer.getGameProfile().getName();
        if (currentName.isBlank() || replacementName == null || replacementName.isBlank()) {
            source.sendError(Text.literal("[CKT] Participante atual ou novo player inválido."));
            return 0;
        }

        long generation = GENERATION.get();
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> Text.literal(
                "[CKT] Substituindo '" + currentName + "' por '" + replacementName + "' no Challonge..."
        ).formatted(Formatting.YELLOW), false);

        CompletableFuture.supplyAsync(() -> {
            try {
                return currentClient.substituteParticipant(currentName, replacementName);
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }, HTTP_EXECUTOR).whenComplete((result, throwable) -> server.execute(() -> {
            if (GENERATION.get() != generation || state != RuntimeState.ON) {
                return;
            }

            Throwable cause = unwrap(throwable);
            if (cause != null) {
                source.sendError(Text.literal("[CKT] Substituição falhou: " + safeMessage(cause)));
                CobbleKantoServerFixes.LOGGER.error(
                        "[CKT] Participant substitution failed: current={} replacement={}.",
                        currentName,
                        replacementName,
                        cause
                );
                return;
            }

            if (result.status() != ChallongeTournamentClient.Status.UPDATED) {
                source.sendError(Text.literal("[CKT] Substituição ignorada com segurança: " + result.detail()));
                return;
            }

            lastSyncStatus = "Substituição OK: " + result.previousName() + " -> " + result.replacementName();
            source.sendFeedback(() -> Text.literal(
                    "[CKT] Substituição OK — " + result.previousName() + " -> " + result.replacementName()
                            + " | participantId=" + result.participantId()
            ).formatted(Formatting.GREEN), true);
            CobbleKantoServerFixes.LOGGER.info(
                    "[CKT] Participant substituted safely: id={} old={} new={}",
                    result.participantId(),
                    result.previousName(),
                    result.replacementName()
            );
            triggerDisplayRefresh(null, "participant-substitution");
        }));
        return 1;
    }

    private static void triggerDisplayRefresh(ServerCommandSource feedbackSource, String reason) {
        TournamentConfig.Snapshot config = TournamentConfig.current();
        ChallongeTournamentClient currentClient = client;
        MinecraftServer server = activeServer;
        if (state != RuntimeState.ON || currentClient == null || server == null) {
            return;
        }
        if (!config.bracketDisplayEnabled()) {
            lastDisplayStatus = "desativado";
            return;
        }
        if (!config.hasBracketDisplayConfig()) {
            lastDisplayStatus = "configuração incompleta";
            if (feedbackSource != null) {
                feedbackSource.sendError(Text.literal(
                        "[CKT] Display incompleto: configure imgbbApiKey e um waterframesUpdateCommand contendo {url}."
                ));
            }
            return;
        }

        long generation = GENERATION.get();
        lastDisplayStatus = "baixando SVG oficial do Challonge (" + reason + ")";
        if (feedbackSource != null) {
            feedbackSource.sendFeedback(() -> Text.literal("[CKT] Baixando a chave oficial do Challonge e publicando o PNG em segundo plano..."), false);
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return TournamentBracketDisplayBridge.renderAndUpload(currentClient, config);
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }, DISPLAY_EXECUTOR).whenComplete((result, throwable) -> server.execute(() -> {
            if (GENERATION.get() != generation || state != RuntimeState.ON) {
                return;
            }

            Throwable cause = unwrap(throwable);
            if (cause != null) {
                lastDisplayStatus = "ERRO: " + safeMessage(cause);
                CobbleKantoServerFixes.LOGGER.error("[CKT] Bracket display publish failed (reason={}).", reason, cause);
                if (feedbackSource != null) {
                    feedbackSource.sendError(Text.literal("[CKT] Falha ao publicar a imagem: " + safeMessage(cause)));
                }
                return;
            }

            try {
                executeDisplayCommand(server, config.waterframesUpdateCommand(), result.imageUrl());
                lastDisplayStatus = "OK: " + result.imageUrl();
                CobbleKantoServerFixes.LOGGER.info(
                        "[CKT] Official Challonge bracket display updated: svgBytes={} pngBytes={} size={}x{} source={} url={}",
                        result.sourceSvgBytes(),
                        result.pngBytes(),
                        result.width(),
                        result.height(),
                        result.sourceSvgUrl(),
                        result.imageUrl()
                );
                if (feedbackSource != null) {
                    feedbackSource.sendFeedback(() -> Text.literal(
                            "[CKT] Display atualizado — imagem oficial do Challonge "
                                    + result.width() + "x" + result.height() + "."
                    ).formatted(Formatting.GREEN), false);
                }
            } catch (Throwable commandFailure) {
                lastDisplayStatus = "PNG publicado, WaterFrames falhou: " + safeMessage(commandFailure);
                CobbleKantoServerFixes.LOGGER.error(
                        "[CKT] PNG uploaded successfully but WaterFrames update command failed. url={}",
                        result.imageUrl(),
                        commandFailure
                );
                if (feedbackSource != null) {
                    feedbackSource.sendError(Text.literal(
                            "[CKT] PNG publicado, mas o comando do WaterFrames falhou: " + safeMessage(commandFailure)
                    ));
                }
            }
        }));
    }

    private static void executeDisplayCommand(MinecraftServer server, String template, String imageUrl) {
        if (template == null || !template.contains("{url}")) {
            throw new IllegalArgumentException("waterframesUpdateCommand must contain {url}.");
        }
        if (template.indexOf('\n') >= 0 || template.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("waterframesUpdateCommand cannot contain line breaks.");
        }
        String command = template.replace("{url}", imageUrl).trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("waterframesUpdateCommand became blank.");
        }
        server.getCommandManager().executeWithPrefix(server.getCommandSource(), "/" + command);
    }

    private static void broadcastVictory(PlayerIdentity winner, PlayerIdentity loser) {
        MinecraftServer server = activeServer;
        if (server == null) {
            return;
        }

        MutableText message = Text.empty()
                .append(Text.literal("CAMPEONATO | Batalha entre ").formatted(Formatting.YELLOW))
                .append(Text.literal(displayName(winner)).formatted(Formatting.YELLOW))
                .append(Text.literal(" e ").formatted(Formatting.YELLOW))
                .append(Text.literal(displayName(loser)).formatted(Formatting.YELLOW))
                .append(Text.literal(" — vitória: ").formatted(Formatting.YELLOW))
                .append(Text.literal(displayName(winner)).formatted(Formatting.GREEN))
                .append(Text.literal("!").formatted(Formatting.YELLOW));
        server.getPlayerManager().broadcast(message, false);
    }

    private static String displayName(PlayerIdentity identity) {
        return identity.displayName().isBlank() ? identity.realName() : identity.displayName();
    }

    private static PlayerIdentity refreshIdentity(PlayerIdentity identity) {
        MinecraftServer server = activeServer;
        if (server == null) {
            return identity;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(identity.uuid());
        if (player == null) {
            return identity;
        }
        return identityFromOnlinePlayer(player);
    }

    private static List<PlayerIdentity> playerIdentitiesFromBattle(Object battle) {
        Object actors = invokeNoArgsQuietly(battle, "getActors");
        return playerIdentitiesFromActors(actors, null);
    }

    private static List<PlayerIdentity> playerIdentitiesFromActors(Object actorsValue, BattlePair cached) {
        if (!(actorsValue instanceof Iterable<?> actors)) {
            return List.of();
        }

        Map<UUID, PlayerIdentity> unique = new LinkedHashMap<>();
        for (Object actor : actors) {
            if (!actorTypeIs(actor, "PLAYER")) {
                continue;
            }
            UUID uuid = actorUuid(actor);
            if (uuid == null) {
                continue;
            }

            PlayerIdentity identity = cached == null ? null : cached.byUuid(uuid);
            if (identity == null) {
                MinecraftServer server = activeServer;
                ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    identity = identityFromOnlinePlayer(player);
                }
            }
            if (identity == null) {
                identity = new PlayerIdentity(uuid, "", actorDisplayName(actor));
            }
            unique.putIfAbsent(uuid, identity);
        }
        return List.copyOf(unique.values());
    }

    private static PlayerIdentity identityFromOnlinePlayer(ServerPlayerEntity player) {
        String realName = player.getGameProfile().getName();
        String displayName = player.getDisplayName() == null ? realName : player.getDisplayName().getString();
        return new PlayerIdentity(player.getUuid(), realName == null ? "" : realName, displayName == null ? "" : displayName);
    }

    private static String actorDisplayName(Object actor) {
        Object name = invokeNoArgsQuietly(actor, "getName");
        if (name == null) {
            return "Jogador";
        }
        Object text = invokeNoArgsQuietly(name, "getString");
        if (text != null && !text.toString().isBlank()) {
            return text.toString();
        }
        return name.toString();
    }

    private static UUID actorUuid(Object actor) {
        Object uuid = invokeNoArgsQuietly(actor, "getUuid");
        return uuid instanceof UUID value ? value : null;
    }

    private static UUID battleId(Object battle) {
        Object id = invokeNoArgsQuietly(battle, "getBattleId");
        return id instanceof UUID value ? value : null;
    }

    private static boolean actorTypeIs(Object actor, String expected) {
        Object type = invokeNoArgsQuietly(actor, "getType");
        return type != null && expected.equalsIgnoreCase(type.toString());
    }

    private static boolean markBattleOnce(UUID battleId) {
        long now = System.currentTimeMillis();
        Long previous = PROCESSED_BATTLES.putIfAbsent(battleId, now);
        pruneCaches();
        return previous == null;
    }

    private static void pruneCaches() {
        long now = System.currentTimeMillis();
        if (ACTIVE_BATTLES.size() > 256) {
            ACTIVE_BATTLES.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis() > CACHE_RETENTION_MILLIS);
        }
        if (PROCESSED_BATTLES.size() > 256) {
            PROCESSED_BATTLES.entrySet().removeIf(entry -> now - entry.getValue() > CACHE_RETENTION_MILLIS);
        }
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

        Method oneArg = Arrays.stream(observable.getClass().getMethods())
                .filter(method -> method.getName().equals("subscribe"))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> Consumer.class.isAssignableFrom(method.getParameterTypes()[0]))
                .findFirst()
                .orElse(null);
        if (oneArg != null) {
            oneArg.invoke(observable, consumer);
            return;
        }

        // Cobblemon's reactive API also exposes priority-based Kotlin subscriptions on some
        // observable implementations. Keep this as a reflection-only fallback so the optional
        // Cobblemon dependency model of ServerFixes is preserved.
        Method prioritized = Arrays.stream(observable.getClass().getMethods())
                .filter(method -> method.getName().equals("subscribe"))
                .filter(method -> method.getParameterCount() == 2)
                .filter(method -> method.getParameterTypes()[1].isInterface())
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(
                        observable.getClass().getName() + ".subscribe(Consumer|Priority, callback) for " + fieldName
                ));

        Class<?> priorityType = prioritized.getParameterTypes()[0];
        Object normalPriority = null;
        if (priorityType.isEnum()) {
            for (Object constant : priorityType.getEnumConstants()) {
                if (constant instanceof Enum<?> enumValue && enumValue.name().equals("NORMAL")) {
                    normalPriority = constant;
                    break;
                }
            }
        }
        if (normalPriority == null) {
            try {
                normalPriority = priorityType.getField("NORMAL").get(null);
            } catch (Throwable ignored) {
            }
        }
        if (normalPriority == null) {
            throw new IllegalStateException("Não foi possível resolver Priority.NORMAL para " + fieldName);
        }

        Class<?> callbackType = prioritized.getParameterTypes()[1];
        Object callback;
        if (Consumer.class.isAssignableFrom(callbackType)) {
            callback = consumer;
        } else {
            callback = java.lang.reflect.Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "CobbleKanto tournament event callback";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                                default -> null;
                            };
                        }
                        if ((method.getName().equals("invoke") || method.getName().equals("accept"))
                                && args != null && args.length >= 1) {
                            consumer.accept(args[0]);
                            if (method.getReturnType() == void.class) {
                                return null;
                            }
                            try {
                                Class<?> unit = Class.forName("kotlin.Unit", false, callbackType.getClassLoader());
                                if (method.getReturnType().isAssignableFrom(unit) || method.getReturnType() == Object.class) {
                                    return unit.getField("INSTANCE").get(null);
                                }
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                        return null;
                    }
            );
        }

        prioritized.invoke(observable, normalPriority, callback);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof RuntimeException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "erro desconhecido";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 220 ? compact.substring(0, 220) + "..." : compact;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private enum RuntimeState {
        OFF,
        STARTING,
        ON
    }

    private record PlayerIdentity(UUID uuid, String realName, String displayName) {
    }

    private record BattlePair(PlayerIdentity first, PlayerIdentity second, long startedAtMillis) {
        PlayerIdentity byUuid(UUID uuid) {
            if (first.uuid().equals(uuid)) {
                return first;
            }
            if (second.uuid().equals(uuid)) {
                return second;
            }
            return null;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cks-challonge-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, throwable) ->
                    CobbleKantoServerFixes.LOGGER.error("Unhandled Challonge worker error.", throwable));
            return thread;
        }
    }
}
