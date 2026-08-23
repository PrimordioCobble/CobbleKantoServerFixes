package net.crulim.cobblekantoserverfixes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class CobblemonPartyPcHuskSyncBridge {
    private static final String DATA_NAMESPACE = "cobblekanto";
    private static final String DATA_VALUE = "cobblemon_party_pc_v5";
    private static final int PAYLOAD_MAGIC = 0x434B5050;
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    private static final int RESTORE_RETRY_INTERVAL_TICKS = 5;
    private static final int RESTORE_VERIFY_DELAY_TICKS = 20;
    private static final int RESTORE_SLOW_RETRY_INTERVAL_TICKS = 100;
    private static final int FAST_RESTORE_ATTEMPTS = 20;

    private static final AtomicBoolean REGISTRATION_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean RETRY_TICK_REGISTERED = new AtomicBoolean(false);
    private static final Map<UUID, PendingRestore> PENDING_RESTORES = new ConcurrentHashMap<>();
    private static final Map<UUID, PartyPcPayload> LAST_GOOD_PAYLOADS = new ConcurrentHashMap<>();

    private static volatile boolean enabled;
    private static volatile boolean seedMode;
    private static volatile boolean debugLogging;
    private static volatile Object huskSyncApi;
    private static volatile Object dataIdentifier;
    private static volatile Class<?> dataInterface;
    private static volatile long serverTick;

    private CobblemonPartyPcHuskSyncBridge() {
    }

    public static void register() {
        enabled = ServerFixesConfig.enabled && ServerFixesConfig.cobblemonPartyPcHuskSyncBridgeEnabled;
        seedMode = ServerFixesConfig.cobblemonPartyPcHuskSyncBridgeSeedMode;
        debugLogging = ServerFixesConfig.logCobblemonPartyPcHuskSyncBridge;

        if (!enabled) {
            info("Cobblemon Party/PC HuskSync bridge disabled by config.");
            return;
        }

        if (!isModLoaded("husksync") || !isModLoaded("cobblemon")) {
            error("Bridge enabled, but one or more required mods are missing: husksync, cobblemon.", null);
            return;
        }

        try {
            registerRetryTick();
            registerWhenHuskSyncReady();
            info("Cobblemon Party/PC HuskSync bridge scheduled for HuskSync API startup (seedMode=" + seedMode + ").");
        } catch (Throwable throwable) {
            error("Could not schedule Cobblemon Party/PC HuskSync bridge registration.", throwable);
        }
    }

    private static void registerRetryTick() {
        if (RETRY_TICK_REGISTERED.compareAndSet(false, true)) {
            ServerTickEvents.END_SERVER_TICK.register(CobblemonPartyPcHuskSyncBridge::tickPendingRestores);
        }
    }

    private static void tickPendingRestores(MinecraftServer server) {
        serverTick++;
        if (PENDING_RESTORES.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, PendingRestore> entry : PENDING_RESTORES.entrySet()) {
            UUID playerId = entry.getKey();
            PendingRestore pending = entry.getValue();
            if (serverTick < pending.nextAttemptTick()) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                continue;
            }

            if (pending.verifying()) {
                try {
                    if (matchesPayload(player, pending.payload())) {
                        if (PENDING_RESTORES.remove(playerId, pending)) {
                            LAST_GOOD_PAYLOADS.put(playerId, pending.payload());
                            debug("Verified restored Party/PC for " + playerName(player) + ": " + pending.payload().description() + ".");
                        }
                    } else {
                        PENDING_RESTORES.replace(playerId, pending, pending.retry(serverTick + 1));
                        error("Cobblemon replaced or changed Party/PC before verification for " + playerName(player)
                                + "; the incoming HuskSync payload will be applied again.", null);
                    }
                } catch (Throwable throwable) {
                    PENDING_RESTORES.replace(playerId, pending, pending.retry(serverTick + RESTORE_RETRY_INTERVAL_TICKS));
                    error("Could not verify restored Party/PC for " + playerName(player)
                            + "; the incoming HuskSync payload remains queued.", throwable);
                }
                continue;
            }

            int attempt = pending.attempts() + 1;
            try {
                applyPayloadNow(player, pending.payload());
                PendingRestore verifying = pending.verifying(attempt, serverTick + RESTORE_VERIFY_DELAY_TICKS);
                PENDING_RESTORES.replace(playerId, pending, verifying);
                debug("Applied queued Party/PC to " + playerName(player) + " on attempt " + attempt
                        + "; verification scheduled in " + RESTORE_VERIFY_DELAY_TICKS + " ticks.");
            } catch (Throwable throwable) {
                int delay = attempt < FAST_RESTORE_ATTEMPTS
                        ? RESTORE_RETRY_INTERVAL_TICKS
                        : RESTORE_SLOW_RETRY_INTERVAL_TICKS;
                PendingRestore retry = pending.failedAttempt(attempt, serverTick + delay);
                PENDING_RESTORES.replace(playerId, pending, retry);
                if (attempt == 1 || attempt == FAST_RESTORE_ATTEMPTS || attempt % 20 == 0) {
                    error("Failed to apply queued Party/PC for " + playerName(player) + " on attempt " + attempt
                            + "; retrying in " + delay + " ticks.", throwable);
                }
            }
        }
    }

    private static void registerWhenHuskSyncReady() throws ReflectiveOperationException {
        Class<?> callbackClass = Class.forName("net.william278.husksync.event.ModLoadedCallback");
        Object event = callbackClass.getField("EVENT").get(null);

        Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, arguments);
                    }
                    if ("post".equals(method.getName()) && arguments != null && arguments.length == 1) {
                        registerBridgeOnce(arguments[0]);
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        invokeEventRegister(event, callback);
    }

    private static void registerBridgeOnce(Object api) {
        if (!REGISTRATION_STARTED.compareAndSet(false, true)) {
            return;
        }

        try {
            huskSyncApi = Objects.requireNonNull(api, "HuskSync API");

            Class<?> identifierClass = Class.forName("net.william278.husksync.data.Identifier");
            dataIdentifier = identifierClass.getMethod("from", String.class, String.class)
                    .invoke(null, DATA_NAMESPACE, DATA_VALUE);
            dataInterface = Class.forName("net.william278.husksync.data.Data");

            registerSerializer();
            registerDataSaveCallback();
            info("Cobblemon Party/PC HuskSync bridge registered as " + DATA_NAMESPACE + ":" + DATA_VALUE + ".");
        } catch (Throwable throwable) {
            REGISTRATION_STARTED.set(false);
            error("Failed to register Cobblemon Party/PC HuskSync bridge.", throwable);
        }
    }

    private static void registerSerializer() throws ReflectiveOperationException {
        Class<?> serializerInterface = Class.forName("net.william278.husksync.data.Serializer");
        Object serializer = Proxy.newProxyInstance(
                serializerInterface.getClassLoader(),
                new Class<?>[]{serializerInterface},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, arguments);
                    }
                    return switch (method.getName()) {
                        case "serialize" -> serializeBridgeData(arguments == null ? null : arguments[0]);
                        case "deserialize" -> newDataProxy(PartyPcPayload.decode((String) arguments[0]));
                        default -> defaultValue(method.getReturnType());
                    };
                }
        );

        Method registerMethod = findMethod(huskSyncApi.getClass(), "registerDataSerializer", 2);
        registerMethod.invoke(huskSyncApi, dataIdentifier, serializer);
    }

    private static void registerDataSaveCallback() throws ReflectiveOperationException {
        Class<?> callbackInterface = Class.forName("net.william278.husksync.event.FabricDataSaveCallback");
        Object event = callbackInterface.getField("EVENT").get(null);

        Object callback = Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class<?>[]{callbackInterface},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, arguments);
                    }
                    if ("invoke".equals(method.getName()) && arguments != null && arguments.length == 1) {
                        captureIntoSnapshot(arguments[0]);
                    }
                    return passResult(method.getReturnType());
                }
        );

        invokeEventRegister(event, callback);
    }

    private static void captureIntoSnapshot(Object event) {
        Object player = null;
        UUID playerId = null;
        try {
            Object user = invokeNoArgs(event, "getUser");
            player = tryInvokeNoArgs(user, "getPlayer");
            if (player == null) {
                debug("Ignoring data-save event because the user is not an online Fabric player.");
                return;
            }

            playerId = findUuid(player);
            if (playerId == null) {
                throw new IllegalStateException("Could not determine the player's UUID.");
            }

            PendingRestore pendingRestore = PENDING_RESTORES.get(playerId);
            PartyPcPayload payload;
            if (pendingRestore != null) {
                payload = pendingRestore.payload();
                debug("Keeping pending Party/PC payload for " + playerName(player) + " because its restore is still pending verification.");
            } else {
                payload = capturePlayerState(player, playerId);
                LAST_GOOD_PAYLOADS.put(playerId, payload);
            }

            PartyPcPayload captured = payload;
            Object data = newDataProxy(captured);
            Method editData = findMethod(event.getClass(), "editData", 1);
            editData.invoke(event, (Consumer<Object>) snapshot -> {
                try {
                    Method setData = findCompatibleMethod(snapshot.getClass(), "setData", dataIdentifier, data);
                    setData.invoke(snapshot, dataIdentifier, data);
                } catch (Throwable throwable) {
                    throw new BridgeRuntimeException("Could not add Cobblemon Party/PC data to the HuskSync snapshot.", throwable);
                }
            });
            debug("Captured Party/PC for " + playerName(player) + ": " + captured.description() + ".");
        } catch (BridgeRuntimeException exception) {
            cancelDataSave(event);
            error(exception.getMessage(), exception.getCause());
        } catch (Throwable throwable) {
            PartyPcPayload fallback = playerId == null ? null : LAST_GOOD_PAYLOADS.get(playerId);
            if (fallback != null) {
                try {
                    Object data = newDataProxy(fallback);
                    Method editData = findMethod(event.getClass(), "editData", 1);
                    editData.invoke(event, (Consumer<Object>) snapshot -> {
                        try {
                            Method setData = findCompatibleMethod(snapshot.getClass(), "setData", dataIdentifier, data);
                            setData.invoke(snapshot, dataIdentifier, data);
                        } catch (Throwable nested) {
                            throw new BridgeRuntimeException("Could not reuse the last valid Cobblemon Party/PC payload.", nested);
                        }
                    });
                    error("Failed to capture current Party/PC for " + playerName(player) + "; reused the last valid in-memory payload instead.", throwable);
                    return;
                } catch (Throwable fallbackFailure) {
                    throwable.addSuppressed(fallbackFailure);
                }
            }
            cancelDataSave(event);
            error("Failed to capture Cobblemon Party/PC; the HuskSync save was cancelled.", throwable);
        }
    }

    private static PartyPcPayload capturePlayerState(Object player, UUID playerId) throws ReflectiveOperationException {
        StoreContext context = getStoreContext(player);
        JsonObject partyJson = saveStoreToJson(context.party(), context.registryAccess());
        JsonObject pcJson = saveStoreToJson(context.pc(), context.registryAccess());
        int partyCount = countPokemon(context.party());
        int pcCount = countPokemon(context.pc());
        int backupCount = countPcBackupPokemon(context.pc());
        validateJsonCounts(partyJson, pcJson, partyCount, pcCount, backupCount);
        return PartyPcPayload.valid(playerId, partyJson.toString(), pcJson.toString(), partyCount, pcCount, backupCount);
    }

    private static Object newDataProxy(PartyPcPayload payload) {
        Objects.requireNonNull(dataInterface, "HuskSync Data interface has not been initialized");
        PartyPcDataHandler handler = new PartyPcDataHandler(payload);
        return Proxy.newProxyInstance(dataInterface.getClassLoader(), new Class<?>[]{dataInterface}, handler);
    }

    private static String serializeBridgeData(Object data) {
        if (data == null || !Proxy.isProxyClass(data.getClass())) {
            throw new IllegalArgumentException("Unexpected Cobblemon Party/PC bridge data object: " + data);
        }
        InvocationHandler handler = Proxy.getInvocationHandler(data);
        if (!(handler instanceof PartyPcDataHandler partyPcDataHandler)) {
            throw new IllegalArgumentException("Unexpected Cobblemon Party/PC bridge data proxy handler.");
        }
        return partyPcDataHandler.payload.encode();
    }

    private static final class PartyPcDataHandler implements InvocationHandler {
        private final PartyPcPayload payload;

        private PartyPcDataHandler(PartyPcPayload payload) {
            this.payload = Objects.requireNonNull(payload, "payload");
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, arguments);
            }
            if ("apply".equals(method.getName()) && arguments != null && arguments.length >= 1) {
                applyPayload(arguments[0], payload);
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static void applyPayload(Object userDataHolder, PartyPcPayload payload) {
        Object player = tryInvokeNoArgs(userDataHolder, "getPlayer");
        UUID playerId = player == null ? null : findUuid(player);

        if (!payload.valid()) {
            error("Ignored an invalid Cobblemon Party/PC payload instead of altering local Pokémon data.", null);
            return;
        }

        if (player == null || playerId == null) {
            error("Cannot apply Cobblemon Party/PC payload: HuskSync holder is not a valid Fabric player holder.", null);
            return;
        }

        if (!playerId.equals(payload.owner())) {
            error("Rejected Cobblemon Party/PC payload because its owner UUID does not match " + playerName(player) + ".", null);
            return;
        }

        if (seedMode) {
            try {
                PartyPcPayload local = capturePlayerState(player, playerId);
                LAST_GOOD_PAYLOADS.put(playerId, local);
                PENDING_RESTORES.remove(playerId);
                info("Seed mode preserved local Party/PC for " + playerName(player) + ": " + local.description() + ".");
            } catch (Throwable throwable) {
                error("Seed mode could not capture local Party/PC for " + playerName(player) + ".", throwable);
            }
            return;
        }

        PENDING_RESTORES.put(playerId, PendingRestore.queued(payload, serverTick + 1));
        debug("Queued incoming Party/PC for " + playerName(player) + ": " + payload.description() + ".");
    }

    private static void applyPayloadNow(Object player, PartyPcPayload payload) throws ReflectiveOperationException {
        UUID playerId = findUuid(player);
        if (playerId == null || !playerId.equals(payload.owner())) {
            throw new IllegalStateException("Cobblemon Party/PC payload owner no longer matches the online player.");
        }

        StoreContext context = getStoreContext(player);
        JsonObject partyJson = parseJsonObject(payload.partyJson());
        JsonObject pcJson = parseJsonObject(payload.pcJson());
        validatePayloadWithFreshStores(payload, partyJson, pcJson, context.registryAccess());

        PartyPcPayload localBeforeApply = capturePlayerState(player, playerId);
        try {
            replaceParty(context.party(), partyJson, context.registryAccess());
            replacePc(context.pc(), pcJson, context.registryAccess());
            initializeStore(context.party());
            initializeStore(context.pc());
            validateAppliedCounts(payload, context.party(), context.pc());
            invokeCompatible(context.storageManager(), "onPlayerDataSync", player);
        } catch (Throwable applyFailure) {
            try {
                replaceParty(context.party(), parseJsonObject(localBeforeApply.partyJson()), context.registryAccess());
                replacePc(context.pc(), parseJsonObject(localBeforeApply.pcJson()), context.registryAccess());
                initializeStore(context.party());
                initializeStore(context.pc());
                invokeCompatible(context.storageManager(), "onPlayerDataSync", player);
            } catch (Throwable rollbackFailure) {
                applyFailure.addSuppressed(rollbackFailure);
            }
            if (applyFailure instanceof ReflectiveOperationException reflectiveOperationException) {
                throw reflectiveOperationException;
            }
            throw new BridgeRuntimeException("Could not apply Cobblemon Party/PC payload.", applyFailure);
        }

        LAST_GOOD_PAYLOADS.put(playerId, payload);
    }

    private static boolean matchesPayload(Object player, PartyPcPayload expected) throws ReflectiveOperationException {
        UUID playerId = findUuid(player);
        if (playerId == null || !playerId.equals(expected.owner())) {
            return false;
        }
        PartyPcPayload actual = capturePlayerState(player, playerId);
        return actual.partyCount() == expected.partyCount()
                && actual.pcCount() == expected.pcCount()
                && actual.backupCount() == expected.backupCount()
                && parseJsonObject(actual.partyJson()).equals(parseJsonObject(expected.partyJson()))
                && parseJsonObject(actual.pcJson()).equals(parseJsonObject(expected.pcJson()));
    }

    private static StoreContext getStoreContext(Object player) throws ReflectiveOperationException {
        Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
        Object cobblemon = cobblemonClass.getField("INSTANCE").get(null);
        Object storageManager = invokeNoArgs(cobblemon, "getStorage");
        Object party = invokeCompatible(storageManager, "getParty", player);
        Object pc = invokeCompatible(storageManager, "getPC", player);
        Method saveMethod = findJsonMethod(party.getClass(), "saveToJSON");
        Object registryAccess = findRegistryAccess(player, saveMethod.getParameterTypes()[1]);
        return new StoreContext(storageManager, party, pc, registryAccess);
    }

    private static JsonObject saveStoreToJson(Object store, Object registryAccess) throws ReflectiveOperationException {
        Method method = findJsonMethod(store.getClass(), "saveToJSON");
        Object result = method.invoke(store, new JsonObject(), registryAccess);
        if (!(result instanceof JsonObject jsonObject)) {
            throw new IllegalStateException("Cobblemon returned a non-JSON object while serializing " + store.getClass().getName() + ".");
        }
        return jsonObject;
    }

    private static void replaceParty(Object party, JsonObject json, Object registryAccess) throws ReflectiveOperationException {
        int slotCount = requiredInt(json, "SlotCount");
        Method slotsGetter = findDeclaredMethodInHierarchy(party.getClass(), "getSlots", 0);
        slotsGetter.setAccessible(true);
        Object slotsValue = slotsGetter.invoke(party);
        if (!(slotsValue instanceof List<?> slots)) {
            throw new IllegalStateException("Cobblemon Party slots are not a mutable List.");
        }
        @SuppressWarnings("unchecked")
        List<Object> mutableSlots = (List<Object>) slots;
        mutableSlots.clear();
        for (int index = 0; index < slotCount; index++) {
            mutableSlots.add(null);
        }
        findJsonMethod(party.getClass(), "loadFromJSON").invoke(party, json, registryAccess);
    }

    private static void replacePc(Object pc, JsonObject json, Object registryAccess) throws ReflectiveOperationException {
        clearCollection(invokeNoArgs(pc, "getBoxes"), "PC boxes");
        Object backupStore = invokeNoArgs(pc, "getBackupStore");
        clearCollection(invokeNoArgs(backupStore, "getPokemon"), "PC backup store");
        clearCollection(invokeNoArgs(pc, "getUnlockedWallpapers"), "PC unlocked wallpapers");
        clearCollection(invokeNoArgs(pc, "getUnseenWallpapers"), "PC unseen wallpapers");
        findJsonMethod(pc.getClass(), "loadFromJSON").invoke(pc, json, registryAccess);
        JsonObject backupJson = json.getAsJsonObject("BackupStore");
        if (backupJson == null) {
            throw new IllegalStateException("Missing PC BackupStore.");
        }
        findJsonMethod(backupStore.getClass(), "loadFromJSON").invoke(backupStore, backupJson, registryAccess);
    }

    private static void clearCollection(Object value, String description) {
        if (value instanceof List<?> list) {
            list.clear();
            return;
        }
        if (value instanceof Set<?> set) {
            set.clear();
            return;
        }
        throw new IllegalStateException(description + " is not a mutable collection.");
    }

    private static void initializeStore(Object store) throws ReflectiveOperationException {
        invokeNoArgs(store, "initialize");
    }

    private static void validatePayloadWithFreshStores(PartyPcPayload payload, JsonObject partyJson, JsonObject pcJson, Object registryAccess) throws ReflectiveOperationException {
        Class<?> partyClass = Class.forName("com.cobblemon.mod.common.api.storage.party.PlayerPartyStore");
        Class<?> pcClass = Class.forName("com.cobblemon.mod.common.api.storage.pc.PCStore");
        Constructor<?> partyConstructor = partyClass.getConstructor(UUID.class);
        Constructor<?> pcConstructor = pcClass.getConstructor(UUID.class);
        Object freshParty = partyConstructor.newInstance(payload.owner());
        Object freshPc = pcConstructor.newInstance(payload.owner());
        replaceParty(freshParty, partyJson, registryAccess);
        replacePc(freshPc, pcJson, registryAccess);
        initializeStore(freshParty);
        initializeStore(freshPc);
        validateAppliedCounts(payload, freshParty, freshPc);
    }

    private static void validateAppliedCounts(PartyPcPayload payload, Object party, Object pc) throws ReflectiveOperationException {
        int partyCount = countPokemon(party);
        int pcCount = countPokemon(pc);
        int backupCount = countPcBackupPokemon(pc);
        if (partyCount != payload.partyCount() || pcCount != payload.pcCount() || backupCount != payload.backupCount()) {
            throw new IllegalStateException("Cobblemon Party/PC count mismatch after load: expected "
                    + payload.partyCount() + "/" + payload.pcCount() + "/" + payload.backupCount()
                    + ", got " + partyCount + "/" + pcCount + "/" + backupCount + ".");
        }
    }

    private static void validateJsonCounts(JsonObject partyJson, JsonObject pcJson, int partyCount, int pcCount, int backupCount) {
        int jsonPartyCount = countPartyJson(partyJson);
        int jsonPcCount = countPcJson(pcJson);
        int jsonBackupCount = countBackupJson(pcJson);
        if (partyCount != jsonPartyCount || pcCount != jsonPcCount || backupCount != jsonBackupCount) {
            throw new IllegalStateException("Cobblemon JSON count mismatch while capturing: store="
                    + partyCount + "/" + pcCount + "/" + backupCount
                    + ", json=" + jsonPartyCount + "/" + jsonPcCount + "/" + jsonBackupCount + ".");
        }
    }

    private static int countPartyJson(JsonObject json) {
        int slotCount = requiredInt(json, "SlotCount");
        int count = 0;
        for (int index = 0; index < slotCount; index++) {
            JsonElement element = json.get("Pokemon" + index);
            if (element != null && element.isJsonObject()) {
                count++;
            }
        }
        return count;
    }

    private static int countPcJson(JsonObject json) {
        int boxCount = requiredInt(json, "BoxCount");
        int count = 0;
        for (int boxIndex = 0; boxIndex < boxCount; boxIndex++) {
            JsonObject box = json.getAsJsonObject("Box" + boxIndex);
            if (box == null) {
                throw new IllegalStateException("Missing PC box " + boxIndex + ".");
            }
            for (int slotIndex = 0; slotIndex < 30; slotIndex++) {
                JsonElement element = box.get("Pokemon" + slotIndex);
                if (element != null && element.isJsonObject()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countBackupJson(JsonObject pcJson) {
        JsonObject backup = pcJson.getAsJsonObject("BackupStore");
        if (backup == null) {
            throw new IllegalStateException("Missing PC BackupStore.");
        }
        int count = 0;
        while (true) {
            JsonElement element = backup.get("Pokemon" + count);
            if (element == null) {
                return count;
            }
            if (!element.isJsonObject()) {
                throw new IllegalStateException("Invalid PC backup Pokémon at index " + count + ".");
            }
            count++;
        }
    }

    private static int requiredInt(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalStateException("Missing numeric Cobblemon JSON field: " + key + ".");
        }
        return element.getAsInt();
    }

    private static int countPokemon(Object store) {
        if (!(store instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("Cobblemon store is not iterable: " + store.getClass().getName());
        }
        int count = 0;
        for (Object pokemon : iterable) {
            if (pokemon != null) {
                count++;
            }
        }
        return count;
    }

    private static int countPcBackupPokemon(Object pc) throws ReflectiveOperationException {
        Object backup = invokeNoArgs(pc, "getBackupStore");
        return countPokemon(backup);
    }

    private static JsonObject parseJsonObject(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Cobblemon Party/PC payload component is not a JSON object.");
        }
        return parsed.getAsJsonObject();
    }

    private static Object findRegistryAccess(Object player, Class<?> registryType) throws ReflectiveOperationException {
        for (Method method : player.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && registryType.isAssignableFrom(method.getReturnType())) {
                Object value = method.invoke(player);
                if (value != null) {
                    return value;
                }
            }
        }
        throw new NoSuchMethodException("Could not locate Cobblemon registry access on " + player.getClass().getName() + ".");
    }

    private static Method findJsonMethod(Class<?> type, String name) throws NoSuchMethodException {
        return findInvocableMethod(type, method -> method.getName().equals(name)
                && method.getParameterCount() == 2
                && method.getParameterTypes()[0].getName().equals("com.google.gson.JsonObject"));
    }

    private static Method findDeclaredMethodInHierarchy(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    private static UUID findUuid(Object player) {
        if (player == null) {
            return null;
        }
        try {
            return (UUID) Arrays.stream(player.getClass().getMethods())
                    .filter(method -> method.getParameterCount() == 0)
                    .filter(method -> method.getReturnType() == UUID.class)
                    .findFirst()
                    .orElseThrow()
                    .invoke(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String playerName(Object player) {
        if (player == null) {
            return "unknown player";
        }
        try {
            Object gameProfile = Arrays.stream(player.getClass().getMethods())
                    .filter(method -> method.getParameterCount() == 0)
                    .filter(method -> method.getReturnType().getName().equals("com.mojang.authlib.GameProfile"))
                    .findFirst()
                    .map(method -> {
                        try {
                            return method.invoke(player);
                        } catch (ReflectiveOperationException ignored) {
                            return null;
                        }
                    })
                    .orElse(null);
            if (gameProfile != null) {
                Object name = tryInvokeNoArgs(gameProfile, "getName");
                if (name instanceof String string && !string.isBlank()) {
                    return string;
                }
            }
        } catch (Throwable ignored) {
        }
        UUID uuid = findUuid(player);
        return uuid == null ? player.toString() : uuid.toString();
    }

    private static boolean isModLoaded(String modId) {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            return (boolean) loaderClass.getMethod("isModLoaded", String.class).invoke(loader, modId);
        } catch (Throwable throwable) {
            error("Could not query Fabric Loader for mod '" + modId + "'.", throwable);
            return false;
        }
    }

    private static void invokeEventRegister(Object event, Object callback) throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
        Method register = eventClass.getMethod("register", Object.class);
        register.invoke(event, callback);
    }

    private static void cancelDataSave(Object event) {
        try {
            Method setCancelled = findMethod(event.getClass(), "setCancelled", 1);
            setCancelled.invoke(event, true);
        } catch (Throwable throwable) {
            error("Could not cancel the incomplete HuskSync save.", throwable);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        return findInvocableMethod(type, method -> method.getName().equals(name)
                && method.getParameterCount() == parameterCount);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object... arguments) throws NoSuchMethodException {
        return findInvocableMethod(type, method -> method.getName().equals(name)
                && method.getParameterCount() == arguments.length
                && parametersAccept(method.getParameterTypes(), arguments));
    }

    private static Method findInvocableMethod(Class<?> type, Predicate<Method> matcher) throws NoSuchMethodException {
        Method publicMethod = findPublicContractMethod(type, matcher, new HashSet<>());
        if (publicMethod != null) {
            return publicMethod;
        }

        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (matcher.test(method) && method.trySetAccessible()) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        throw new NoSuchMethodException(type.getName() + " matching invocable method");
    }

    private static Method findPublicContractMethod(Class<?> type, Predicate<Method> matcher, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return null;
        }

        if (Modifier.isPublic(type.getModifiers())) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && matcher.test(method)) {
                    return method;
                }
            }
        }

        for (Class<?> interfaceType : type.getInterfaces()) {
            Method method = findPublicContractMethod(interfaceType, matcher, visited);
            if (method != null) {
                return method;
            }
        }

        return findPublicContractMethod(type.getSuperclass(), matcher, visited);
    }

    private static boolean parametersAccept(Class<?>[] parameterTypes, Object[] arguments) {
        for (int index = 0; index < parameterTypes.length; index++) {
            if (arguments[index] != null && !parameterTypes[index].isInstance(arguments[index])) {
                return false;
            }
        }
        return true;
    }

    private static Object invokeCompatible(Object target, String methodName, Object... arguments) throws ReflectiveOperationException {
        return findCompatibleMethod(target.getClass(), methodName, arguments).invoke(target, arguments);
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        return findMethod(target.getClass(), methodName, 0).invoke(target);
    }

    private static Object tryInvokeNoArgs(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return invokeNoArgs(target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object passResult(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType.isEnum()) {
            for (Object constant : returnType.getEnumConstants()) {
                if (constant instanceof Enum<?> enumValue && enumValue.name().equals("PASS")) {
                    return constant;
                }
            }
        }
        try {
            Field pass = returnType.getField("PASS");
            return pass.get(null);
        } catch (Throwable ignored) {
            return defaultValue(returnType);
        }
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "CobbleKanto Cobblemon Party/PC HuskSync bridge proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static void info(String message) {
        CobbleKantoServerFixes.LOGGER.info("[CobblemonPartyPcBridge] {}", message);
    }

    private static void debug(String message) {
        if (debugLogging) {
            CobbleKantoServerFixes.LOGGER.info("[CobblemonPartyPcBridge/DEBUG] {}", message);
        }
    }

    private static void error(String message, Throwable throwable) {
        if (throwable == null) {
            CobbleKantoServerFixes.LOGGER.error("[CobblemonPartyPcBridge] {}", message);
        } else {
            CobbleKantoServerFixes.LOGGER.error("[CobblemonPartyPcBridge] " + message, throwable);
        }
    }

    private record StoreContext(Object storageManager, Object party, Object pc, Object registryAccess) {
    }

    private record PendingRestore(PartyPcPayload payload, int attempts, long nextAttemptTick, boolean verifying) {
        static PendingRestore queued(PartyPcPayload payload, long nextAttemptTick) {
            return new PendingRestore(payload, 0, nextAttemptTick, false);
        }

        PendingRestore failedAttempt(int attempts, long nextAttemptTick) {
            return new PendingRestore(payload, attempts, nextAttemptTick, false);
        }

        PendingRestore verifying(int attempts, long nextAttemptTick) {
            return new PendingRestore(payload, attempts, nextAttemptTick, true);
        }

        PendingRestore retry(long nextAttemptTick) {
            return new PendingRestore(payload, attempts, nextAttemptTick, false);
        }
    }

    private record PartyPcPayload(
            boolean valid,
            UUID owner,
            String partyJson,
            String pcJson,
            int partyCount,
            int pcCount,
            int backupCount
    ) {
        private PartyPcPayload {
            partyJson = partyJson == null ? "" : partyJson;
            pcJson = pcJson == null ? "" : pcJson;
        }

        static PartyPcPayload valid(UUID owner, String partyJson, String pcJson, int partyCount, int pcCount, int backupCount) {
            if (owner == null || partyJson.isBlank() || pcJson.isBlank() || partyCount < 0 || pcCount < 0 || backupCount < 0) {
                throw new IllegalArgumentException("Invalid Cobblemon Party/PC payload values.");
            }
            return new PartyPcPayload(true, owner, partyJson, pcJson, partyCount, pcCount, backupCount);
        }

        static PartyPcPayload invalid() {
            return new PartyPcPayload(false, null, "", "", 0, 0, 0);
        }

        String encode() {
            if (!valid) {
                throw new IllegalStateException("Invalid Cobblemon Party/PC data cannot be serialized.");
            }
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(output);
                     DataOutputStream data = new DataOutputStream(gzip)) {
                    data.writeInt(PAYLOAD_MAGIC);
                    data.writeInt(PAYLOAD_VERSION);
                    data.writeLong(owner.getMostSignificantBits());
                    data.writeLong(owner.getLeastSignificantBits());
                    data.writeInt(partyCount);
                    data.writeInt(pcCount);
                    data.writeInt(backupCount);
                    writeString(data, partyJson);
                    writeString(data, pcJson);
                }
                return Base64.getEncoder().encodeToString(output.toByteArray());
            } catch (IOException exception) {
                throw new IllegalStateException("Could not encode Cobblemon Party/PC payload.", exception);
            }
        }

        static PartyPcPayload decode(String encoded) {
            try {
                if (encoded == null || encoded.isBlank()) {
                    return invalid();
                }
                byte[] compressed = Base64.getDecoder().decode(encoded);
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
                     DataInputStream data = new DataInputStream(gzip)) {
                    if (data.readInt() != PAYLOAD_MAGIC || data.readInt() != PAYLOAD_VERSION) {
                        return invalid();
                    }
                    UUID owner = new UUID(data.readLong(), data.readLong());
                    int partyCount = data.readInt();
                    int pcCount = data.readInt();
                    int backupCount = data.readInt();
                    String partyJson = readString(data);
                    String pcJson = readString(data);
                    if (data.read() != -1) {
                        return invalid();
                    }
                    PartyPcPayload payload = valid(owner, partyJson, pcJson, partyCount, pcCount, backupCount);
                    JsonObject party = parseJsonObject(payload.partyJson());
                    JsonObject pc = parseJsonObject(payload.pcJson());
                    validateJsonCounts(party, pc, payload.partyCount(), payload.pcCount(), payload.backupCount());
                    return payload;
                }
            } catch (Throwable ignored) {
                return invalid();
            }
        }

        String description() {
            return "party=" + partyCount + ", pc=" + pcCount + ", backup=" + backupCount;
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IOException("Cobblemon JSON payload exceeds the maximum supported size.");
            }
            output.writeInt(bytes.length);
            output.write(bytes);
        }

        private static String readString(DataInputStream input) throws IOException {
            int length = input.readInt();
            if (length < 0 || length > MAX_JSON_BYTES) {
                throw new IOException("Invalid Cobblemon JSON payload length: " + length);
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) {
                throw new IOException("Unexpected end of Cobblemon JSON payload.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final class BridgeRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private BridgeRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
