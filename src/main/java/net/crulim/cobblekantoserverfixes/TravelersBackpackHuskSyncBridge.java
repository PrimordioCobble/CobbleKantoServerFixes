package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class TravelersBackpackHuskSyncBridge {
    private static final String DATA_NAMESPACE = "cobblekanto";
    private static final String DATA_VALUE = "travelers_backpack_accessory";
    private static final String PAYLOAD_VERSION = "1";

    private static final AtomicBoolean REGISTRATION_STARTED = new AtomicBoolean(false);
    private static final Map<UUID, BackpackPayload> PENDING_RESTORES = new ConcurrentHashMap<>();

    private static volatile boolean enabled;
    private static volatile boolean seedMode;
    private static volatile boolean debugLogging;
    private static volatile Object huskSyncApi;
    private static volatile Object dataIdentifier;
    private static volatile Class<?> dataInterface;
    private static volatile Class<?> serializerInterface;
    private static volatile Class<?> inventoryDataClass;
    private static volatile Object inventorySerializer;

    private TravelersBackpackHuskSyncBridge() {
    }

    public static void register() {
        enabled = ServerFixesConfig.enabled && ServerFixesConfig.travelersBackpackHuskSyncBridgeEnabled;
        seedMode = ServerFixesConfig.travelersBackpackHuskSyncBridgeSeedMode;
        debugLogging = ServerFixesConfig.logTravelersBackpackHuskSyncBridge;

        if (!enabled) {
            info("Traveler's Backpack/HuskSync bridge disabled by config.");
            return;
        }

        if (!isModLoaded("husksync") || !isModLoaded("accessories") || !isModLoaded("travelersbackpack")) {
            error("Bridge enabled, but one or more required mods are missing: husksync, accessories, travelersbackpack.", null);
            return;
        }

        try {
            registerWhenHuskSyncReady();
            info("Traveler's Backpack/HuskSync bridge scheduled for HuskSync API startup (seedMode=" + seedMode + ").");
        } catch (Throwable throwable) {
            error("Could not schedule Traveler's Backpack/HuskSync bridge registration.", throwable);
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
            serializerInterface = Class.forName("net.william278.husksync.data.Serializer");
            inventoryDataClass = Class.forName("net.william278.husksync.data.FabricData$Items$Inventory");
            Object inventoryIdentifier = identifierClass.getField("INVENTORY").get(null);
            Object serializerOptional = findCompatibleMethod(huskSyncApi.getClass(), "getDataSerializer", inventoryIdentifier)
                    .invoke(huskSyncApi, inventoryIdentifier);
            if (!(serializerOptional instanceof java.util.Optional<?> optional) || optional.isEmpty()) {
                throw new IllegalStateException("HuskSync inventory serializer is unavailable.");
            }
            inventorySerializer = optional.get();

            registerSerializer();
            registerDataSaveCallback();
            info("Traveler's Backpack/HuskSync bridge registered as " + DATA_NAMESPACE + ":" + DATA_VALUE + ".");
        } catch (Throwable throwable) {
            REGISTRATION_STARTED.set(false);
            error("Failed to register Traveler's Backpack/HuskSync bridge.", throwable);
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
                        case "deserialize" -> newDataProxy(BackpackPayload.decode((String) arguments[0]));
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
        try {
            Object user = invokeNoArgs(event, "getUser");
            Object player = tryInvokeNoArgs(user, "getPlayer");
            if (player == null) {
                debug("Ignoring data-save event because the user is not an online Fabric player.");
                return;
            }

            UUID playerId = findUuid(player);
            BackpackPayload payload = playerId == null ? null : PENDING_RESTORES.get(playerId);
            if (payload != null) {
                debug("Keeping pending backpack payload for " + playerName(player) + " because its previous restore was not completed.");
            } else {
                Object equippedStack = findEquippedTravelersBackpack(player);
                payload = equippedStack == null
                        ? BackpackPayload.empty()
                        : BackpackPayload.present(serializeItemStack(equippedStack));
            }

            BackpackPayload captured = payload;
            Object data = newDataProxy(captured);
            Method editData = findMethod(event.getClass(), "editData", 1);
            editData.invoke(event, (Consumer<Object>) snapshot -> {
                try {
                    Method setData = findCompatibleMethod(snapshot.getClass(), "setData", dataIdentifier, data);
                    setData.invoke(snapshot, dataIdentifier, data);
                } catch (Throwable throwable) {
                    throw new BridgeRuntimeException("Could not add backpack data to the HuskSync snapshot.", throwable);
                }
            });
            debug("Captured equipped backpack state for " + playerName(player) + ": " + captured.description() + ".");
        } catch (BridgeRuntimeException exception) {
            cancelDataSave(event);
            error(exception.getMessage(), exception.getCause());
        } catch (Throwable throwable) {
            cancelDataSave(event);
            error("Failed to capture the equipped Traveler's Backpack; the HuskSync save was cancelled.", throwable);
        }
    }

    private static Object newDataProxy(BackpackPayload payload) {
        Objects.requireNonNull(dataInterface, "HuskSync Data interface has not been initialized");
        BackpackDataHandler handler = new BackpackDataHandler(payload);
        return Proxy.newProxyInstance(dataInterface.getClassLoader(), new Class<?>[]{dataInterface}, handler);
    }

    private static String serializeBridgeData(Object data) {
        if (data == null || !Proxy.isProxyClass(data.getClass())) {
            throw new IllegalArgumentException("Unexpected Traveler's Backpack bridge data object: " + data);
        }
        InvocationHandler handler = Proxy.getInvocationHandler(data);
        if (!(handler instanceof BackpackDataHandler backpackDataHandler)) {
            throw new IllegalArgumentException("Unexpected Traveler's Backpack bridge data proxy handler.");
        }
        return backpackDataHandler.payload.encode();
    }

    private static final class BackpackDataHandler implements InvocationHandler {
        private final BackpackPayload payload;

        private BackpackDataHandler(BackpackPayload payload) {
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

    private static void applyPayload(Object userDataHolder, BackpackPayload payload) {
        if (!payload.valid()) {
            error("Ignored an invalid Traveler's Backpack payload instead of altering the player's equipped slot.", null);
            return;
        }

        try {
            Object player = tryInvokeNoArgs(userDataHolder, "getPlayer");
            if (player == null) {
                error("Cannot apply Traveler's Backpack payload: HuskSync holder is not a Fabric player holder.", null);
                return;
            }

            UUID playerId = findUuid(player);
            Object currentEntry = findEquippedTravelersBackpackEntry(player);

            if (seedMode) {

                
                if (playerId != null) {
                    PENDING_RESTORES.remove(playerId);
                }
                info("Seed mode preserved the local equipped-backpack state for " + playerName(player)
                        + " (" + (currentEntry == null ? "EMPTY" : "PRESENT") + ").");
                return;
            }

            if (!payload.present()) {
                if (currentEntry != null) {
                    Object currentStack = invokeNoArgs(currentEntry, "stack");
                    Object emptyStack = findEmptyItemStack(currentStack.getClass());
                    if (!setEntryStack(currentEntry, emptyStack)) {
                        throw new IllegalStateException("Accessories rejected clearing the equipped backpack slot.");
                    }
                }
                if (playerId != null) {
                    PENDING_RESTORES.remove(playerId);
                }
                debug("Applied EMPTY equipped backpack state for " + playerName(player) + ".");
                return;
            }

            Object restoredStack = deserializeItemStack(payload.itemPayload());
            if (restoredStack == null || !isTravelersBackpackStack(restoredStack)) {
                throw new IllegalStateException("Decoded custom data was not a Traveler's Backpack ItemStack.");
            }

            boolean restored;
            if (currentEntry != null) {
                restored = setEntryStack(currentEntry, restoredStack);
            } else {
                restored = attemptToEquip(player, restoredStack);
            }

            if (!restored) {
                if (playerId != null) {
                    PENDING_RESTORES.put(playerId, payload);
                }
                error("Could not restore the equipped Traveler's Backpack for " + playerName(player)
                        + ". The payload is being retained and will not be overwritten by an empty save.", null);
                return;
            }

            if (playerId != null) {
                PENDING_RESTORES.remove(playerId);
            }
            debug("Restored equipped Traveler's Backpack for " + playerName(player) + ".");
        } catch (Throwable throwable) {
            Object player = tryInvokeNoArgs(userDataHolder, "getPlayer");
            UUID playerId = player == null ? null : findUuid(player);
            if (payload.present() && playerId != null) {
                PENDING_RESTORES.put(playerId, payload);
            }
            error("Failed to apply Traveler's Backpack custom data. No explicit empty state will replace the pending payload.", throwable);
        }
    }

    private static String serializeItemStack(Object stack) throws ReflectiveOperationException {
        Method from = findInvocableMethod(inventoryDataClass, method -> method.getName().equals("from")
                && Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 2
                && method.getParameterTypes()[0].isArray()
                && method.getParameterTypes()[1] == int.class
                && method.getParameterTypes()[0].getComponentType().isInstance(stack));
        Class<?> componentType = from.getParameterTypes()[0].getComponentType();
        Object stackArray = Array.newInstance(componentType, 1);
        Array.set(stackArray, 0, stack);
        Object inventoryData = from.invoke(null, stackArray, 0);

        Method serialize = serializerInterface.getMethod("serialize", dataInterface);
        Object serialized = serialize.invoke(inventorySerializer, inventoryData);
        if (!(serialized instanceof String string) || string.isBlank()) {
            throw new IllegalStateException("HuskSync returned an empty ItemStack serialization.");
        }
        return string;
    }

    private static Object deserializeItemStack(String serialized) throws ReflectiveOperationException {
        Method deserialize = serializerInterface.getMethod("deserialize", String.class);
        Object inventoryData = deserialize.invoke(inventorySerializer, serialized);
        Object contents = invokeNoArgs(inventoryData, "getContents");
        return contents != null && Array.getLength(contents) > 0 ? Array.get(contents, 0) : null;
    }

    private static Object findEquippedTravelersBackpack(Object player) throws ReflectiveOperationException {
        Object entry = findEquippedTravelersBackpackEntry(player);
        return entry == null ? null : invokeNoArgs(entry, "stack");
    }

    private static Object findEquippedTravelersBackpackEntry(Object player) throws ReflectiveOperationException {
        Object capability = getAccessoriesCapability(player);
        if (capability == null) {
            return null;
        }

        Predicate<Object> predicate = TravelersBackpackHuskSyncBridge::isTravelersBackpackStack;
        Method method = findInvocableMethod(capability.getClass(), candidate -> candidate.getName().equals("getFirstEquipped")
                && candidate.getParameterCount() == 1
                && Predicate.class.isAssignableFrom(candidate.getParameterTypes()[0]));
        return method.invoke(capability, predicate);
    }

    private static Object getAccessoriesCapability(Object player) throws ReflectiveOperationException {
        Class<?> capabilityClass = Class.forName("io.wispforest.accessories.api.AccessoriesCapability");
        Method getMethod = findInvocableMethod(capabilityClass, candidate -> candidate.getName().equals("get")
                && Modifier.isStatic(candidate.getModifiers())
                && candidate.getParameterCount() == 1);
        return getMethod.invoke(null, player);
    }

    private static boolean attemptToEquip(Object player, Object stack) throws ReflectiveOperationException {
        Object capability = getAccessoriesCapability(player);
        if (capability == null) {
            return false;
        }

        Method method = findCompatibleMethod(capability.getClass(), "attemptToEquipAccessory", stack);
        return method.invoke(capability, stack) != null;
    }

    private static boolean setEntryStack(Object entry, Object stack) throws ReflectiveOperationException {
        Object reference = invokeNoArgs(entry, "reference");
        Method setStack = findMethod(reference.getClass(), "setStack", 1);
        Object result = setStack.invoke(reference, stack);
        return !(result instanceof Boolean bool) || bool;
    }

    private static boolean isTravelersBackpackStack(Object stack) {
        if (stack == null) {
            return false;
        }
        try {
            Method itemGetter = Arrays.stream(stack.getClass().getMethods())
                    .filter(method -> method.getParameterCount() == 0)
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getName().equals("getItem")
                            || method.getReturnType().getName().equals("net.minecraft.class_1792")
                            || method.getReturnType().getSimpleName().equals("Item"))
                    .findFirst()
                    .orElse(null);
            if (itemGetter == null) {
                return false;
            }
            Object item = itemGetter.invoke(stack);
            if (item == null) {
                return false;
            }
            String className = item.getClass().getName().toLowerCase(Locale.ROOT);
            return className.contains("travelersbackpack")
                    && (className.contains("backpackitem") || className.contains("travelersbackpackitem"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findEmptyItemStack(Class<?> stackClass) throws ReflectiveOperationException {
        for (Field field : stackClass.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && stackClass.isAssignableFrom(field.getType())) {
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            }
        }
        for (Field field : stackClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && stackClass.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            }
        }
        throw new NoSuchFieldException("Could not locate ItemStack.EMPTY on " + stackClass.getName());
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
            case "toString" -> "CobbleKanto Traveler's Backpack/HuskSync bridge proxy";
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
        CobbleKantoServerFixes.LOGGER.info("[BackpackBridge] {}", message);
    }

    private static void debug(String message) {
        if (debugLogging) {
            CobbleKantoServerFixes.LOGGER.info("[BackpackBridge/DEBUG] {}", message);
        }
    }

    private static void error(String message, Throwable throwable) {
        if (throwable == null) {
            CobbleKantoServerFixes.LOGGER.error("[BackpackBridge] {}", message);
        } else {
            CobbleKantoServerFixes.LOGGER.error("[BackpackBridge] " + message, throwable);
        }
    }

    private record BackpackPayload(boolean valid, boolean present, String itemPayload) {
        private BackpackPayload {
            itemPayload = itemPayload == null ? "" : itemPayload;
        }

        static BackpackPayload empty() {
            return new BackpackPayload(true, false, "");
        }

        static BackpackPayload present(String itemPayload) {
            if (itemPayload == null || itemPayload.isBlank()) {
                throw new IllegalArgumentException("A present backpack payload cannot be empty.");
            }
            return new BackpackPayload(true, true, itemPayload);
        }

        static BackpackPayload invalid() {
            return new BackpackPayload(false, false, "");
        }

        String encode() {
            if (!valid) {
                throw new IllegalStateException("Invalid backpack data cannot be serialized.");
            }
            if (!present) {
                return PAYLOAD_VERSION + "|EMPTY";
            }
            String base64 = Base64.getEncoder().encodeToString(itemPayload.getBytes(StandardCharsets.UTF_8));
            return PAYLOAD_VERSION + "|PRESENT|" + base64;
        }

        static BackpackPayload decode(String encoded) {
            try {
                if (encoded == null || encoded.isBlank()) {
                    return invalid();
                }
                String[] parts = encoded.split("\\|", 3);
                if (parts.length < 2 || !PAYLOAD_VERSION.equals(parts[0])) {
                    return invalid();
                }
                if ("EMPTY".equals(parts[1])) {
                    return empty();
                }
                if (!"PRESENT".equals(parts[1]) || parts.length != 3) {
                    return invalid();
                }
                String item = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                return item.isBlank() ? invalid() : present(item);
            } catch (Throwable ignored) {
                return invalid();
            }
        }

        String description() {
            return valid ? (present ? "PRESENT" : "EMPTY") : "INVALID";
        }
    }

    private static final class BridgeRuntimeException extends RuntimeException {
        private BridgeRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
