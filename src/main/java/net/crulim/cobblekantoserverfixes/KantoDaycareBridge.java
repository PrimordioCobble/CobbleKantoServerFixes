package net.crulim.cobblekantoserverfixes;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class KantoDaycareBridge {
    private static final Path CELLS_PATH = Path.of("config", "cobblekanto", "daycare_cells.tsv");
    private static final Identifier PASTURE_ID = Identifier.tryParse("cobblemon:pasture");
    private static final String DITTO_SPECIES = "ditto";
    private static final int TECHNICAL_CAGE_HEIGHT = 14;
    private static final int TECHNICAL_GUI_OPEN_DELAY_TICKS = 5;
    private static final int TECHNICAL_PENDING_SESSION_TIMEOUT_TICKS = 200;
    private static final int LEGACY_GRID_WIDTH = 32;
    private static final int LEGACY_CELL_SPACING_BLOCKS = 16;
    private static final int LEGACY_CELL_CENTER_OFFSET = 3;
    private static final int CELL_CENTER_IN_CHUNK = 8;
    private static final int DAYCARE_TICKET_RADIUS = 1;

    private static final Map<UUID, DaycareCell> cellsByPlayer = new LinkedHashMap<>();
    private static final Map<UUID, PendingDaycareOpen> pendingOpens = new HashMap<>();
    private static ChunkTicketType<UUID> daycareChunkTicketType;
    private static final Map<String, String> KANTO_OFFSPRING_OVERRIDES = Map.ofEntries(
            Map.entry("pichu", "pikachu"),
            Map.entry("pikachu", "pikachu"),
            Map.entry("raichu", "pikachu"),
            Map.entry("cleffa", "clefairy"),
            Map.entry("clefairy", "clefairy"),
            Map.entry("clefable", "clefairy"),
            Map.entry("igglybuff", "jigglypuff"),
            Map.entry("jigglypuff", "jigglypuff"),
            Map.entry("wigglytuff", "jigglypuff"),
            Map.entry("tyrogue", "hitmonlee"),
            Map.entry("hitmonlee", "hitmonlee"),
            Map.entry("hitmonchan", "hitmonchan"),
            Map.entry("smoochum", "jynx"),
            Map.entry("jynx", "jynx"),
            Map.entry("elekid", "electabuzz"),
            Map.entry("electabuzz", "electabuzz"),
            Map.entry("magby", "magmar"),
            Map.entry("magmar", "magmar"),
            Map.entry("mimejr", "mrmime"),
            Map.entry("mrmime", "mrmime"),
            Map.entry("happiny", "chansey"),
            Map.entry("chansey", "chansey"),
            Map.entry("blissey", "chansey"),
            Map.entry("munchlax", "snorlax"),
            Map.entry("snorlax", "snorlax")
    );
    private static final Set<String> KANTO_SPECIES = Set.of(
            "bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon", "charizard", "squirtle", "wartortle", "blastoise",
            "caterpie", "metapod", "butterfree", "weedle", "kakuna", "beedrill", "pidgey", "pidgeotto", "pidgeot",
            "rattata", "raticate", "spearow", "fearow", "ekans", "arbok", "pikachu", "raichu", "sandshrew", "sandslash",
            "nidoranf", "nidorina", "nidoqueen", "nidoranm", "nidorino", "nidoking", "clefairy", "clefable", "vulpix", "ninetales",
            "jigglypuff", "wigglytuff", "zubat", "golbat", "oddish", "gloom", "vileplume", "paras", "parasect", "venonat", "venomoth",
            "diglett", "dugtrio", "meowth", "persian", "psyduck", "golduck", "mankey", "primeape", "growlithe", "arcanine",
            "poliwag", "poliwhirl", "poliwrath", "abra", "kadabra", "alakazam", "machop", "machoke", "machamp", "bellsprout",
            "weepinbell", "victreebel", "tentacool", "tentacruel", "geodude", "graveler", "golem", "ponyta", "rapidash", "slowpoke",
            "slowbro", "magnemite", "magneton", "farfetchd", "doduo", "dodrio", "seel", "dewgong", "grimer", "muk", "shellder",
            "cloyster", "gastly", "haunter", "gengar", "onix", "drowzee", "hypno", "krabby", "kingler", "voltorb", "electrode",
            "exeggcute", "exeggutor", "cubone", "marowak", "hitmonlee", "hitmonchan", "lickitung", "koffing", "weezing", "rhyhorn",
            "rhydon", "chansey", "tangela", "kangaskhan", "horsea", "seadra", "goldeen", "seaking", "staryu", "starmie", "mrmime",
            "scyther", "jynx", "electabuzz", "magmar", "pinsir", "tauros", "magikarp", "gyarados", "lapras", "ditto", "eevee",
            "vaporeon", "jolteon", "flareon", "porygon", "omanyte", "omastar", "kabuto", "kabutops", "aerodactyl", "snorlax",
            "articuno", "zapdos", "moltres", "dratini", "dragonair", "dragonite", "mewtwo", "mew"
    );
    private static long serverTicks = 0;
    private static boolean loadedCells = false;
    private static boolean kantoOnlyEggHookRegistered = false;
    private static boolean kantoOnlyEggHookRegistrationFailed = false;

    private KantoDaycareBridge() {
    }

    public static void register() {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled) {
            CobbleKantoServerFixes.LOGGER.info("Kanto Daycare bridge disabled by config; no Daycare callbacks were registered.");
            return;
        }

        loadCells();
        daycareChunkTicketType = ChunkTicketType.create(
                "cobblekanto_daycare_teleport",
                Comparator.<UUID>naturalOrder(),
                Math.max(20, ServerFixesConfig.kantoDaycareForceLoadTicks)
        );
        CommandRegistrationCallback.EVENT.register(KantoDaycareBridge::registerCommands);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTicks++;
            updatePendingOpens(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (ServerFixesConfig.kantoDaycareCleanupManagedForcedChunksOnStart) {
                int removed = cleanupManagedPersistentForcedChunks(server);
                if (removed > 0) {
                    CobbleKantoServerFixes.LOGGER.warn("Removed {} legacy persistent KantoDaycare forceload(s) on startup.", removed);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> pendingOpens.clear());
        UseBlockCallback.EVENT.register(KantoDaycareBridge::handleAdventureIncubatorUse);
        registerKantoOnlyEggHookIfPossible();
    }

    private static ActionResult handleAdventureIncubatorUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.PASS;
        }
        return tryHandleAdventureIncubatorUse(serverPlayer, world, hand, hitResult);
    }

    public static ActionResult tryHandleAdventureIncubatorUse(ServerPlayerEntity serverPlayer, World world, Hand hand, BlockHitResult hitResult) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled) {
            return ActionResult.PASS;
        }
        if (world.isClient()) {
            return ActionResult.PASS;
        }
        if (serverPlayer.interactionManager.getGameMode() != GameMode.ADVENTURE) {
            return ActionResult.PASS;
        }

        BlockState clickedState = world.getBlockState(hitResult.getBlockPos());
        Block pastureBlock = getPastureBlock();
        if (pastureBlock == null || !clickedState.isOf(pastureBlock)) {
            return ActionResult.PASS;
        }

        ItemStack stack = serverPlayer.getStackInHand(hand);
        if (stack.isEmpty() || !isDaycareIncubatorStack(stack)) {
            return ActionResult.PASS;
        }

        try {
            // Run Daycare+'s incubator logic directly before vanilla Adventure-mode interaction gates can block it.
            // The initial use() calls claim/initialise the incubator storage; useOnBlock() then withdraws eggs.
            ActionResult lastResult = ActionResult.PASS;
            for (int attempt = 0; attempt < 3; attempt++) {
                ItemStack currentStack = serverPlayer.getStackInHand(hand);
                currentStack.getItem().use(world, serverPlayer, hand);
                lastResult = currentStack.getItem().useOnBlock(new ItemUsageContext(serverPlayer, hand, hitResult));
                if (lastResult == ActionResult.SUCCESS || lastResult == ActionResult.CONSUME || lastResult == ActionResult.CONSUME_PARTIAL) {
                    return lastResult;
                }
            }
            return lastResult == ActionResult.PASS ? ActionResult.SUCCESS : lastResult;
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.error("Failed to proxy Daycare+ incubator use in Adventure mode for {}.", serverPlayer.getGameProfile().getName(), exception);
            return ActionResult.FAIL;
        }
    }

    private static boolean isDaycareIncubatorStack(ItemStack stack) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) {
            return false;
        }
        String namespace = itemId.getNamespace();
        String path = itemId.getPath();
        if (!"daycareplus".equals(namespace)) {
            return false;
        }
        return path.endsWith("_incubator") || path.contains("incubator");
    }

    private static void registerKantoOnlyEggHookIfPossible() {
        if (kantoOnlyEggHookRegistered || kantoOnlyEggHookRegistrationFailed) {
            return;
        }

        try {
            ClassLoader classLoader = KantoDaycareBridge.class.getClassLoader();
            Class<?> eventsClass = Class.forName(
                    "com.provismet.cobblemon.daycareplus.api.DaycarePlusEvents",
                    false,
                    classLoader
            );
            Field eventField = eventsClass.getField("EGG_PROPERTIES_CREATED");
            Object rawEvent = eventField.get(null);
            if (!(rawEvent instanceof Event<?> event)) {
                kantoOnlyEggHookRegistrationFailed = true;
                CobbleKantoServerFixes.LOGGER.error(
                        "Kanto Daycare #151 hook found EGG_PROPERTIES_CREATED, but it is not a Fabric Event (actual type: {}).",
                        rawEvent == null ? "null" : rawEvent.getClass().getName()
                );
                return;
            }

            // Important: invoke Event#invoker and Event#register through Fabric's public
            // interface. Reflecting on event.getClass() reaches Fabric's package-private
            // ArrayBackedEvent implementation and fails on Java 21 with IllegalAccessException.
            Object invoker = event.invoker();
            Class<?> listenerType = findListenerTypeWithMethod(invoker, "modifyProperties");
            if (listenerType == null) {
                kantoOnlyEggHookRegistrationFailed = true;
                CobbleKantoServerFixes.LOGGER.error(
                        "Kanto Daycare #151 hook could not find the EGG_PROPERTIES_CREATED listener interface."
                );
                return;
            }

            Object listener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return handleProxyObjectMethod(proxy, method, args);
                        }
                        if ("modifyProperties".equals(method.getName()) && args != null && args.length >= 3) {
                            forceKantoOnlyEggProperties(args[0], args[1], args[2]);
                        }
                        return defaultValue(method.getReturnType());
                    }
            );

            registerFabricEventListener(event, listener);
            kantoOnlyEggHookRegistered = true;
            CobbleKantoServerFixes.LOGGER.info(
                    "Kanto Daycare #151 offspring hook registered through the public Fabric Event API."
            );
        } catch (ClassNotFoundException exception) {
            // Daycare+ may initialize after this mod. Keep this retryable; openForPlayer()
            // will try again once Daycare+ is known to be available.
            CobbleKantoServerFixes.LOGGER.warn(
                    "Daycare+ API is not available yet; the #151 hook will be retried when the Daycare opens."
            );
        } catch (Throwable throwable) {
            // Do not retry a structurally incompatible hook on every GUI open. The mixins
            // remain as a second enforcement layer, and the rest of the bridge stays usable.
            kantoOnlyEggHookRegistrationFailed = true;
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to register the Kanto Daycare #151 hook; reflective retries are disabled for this run.",
                    throwable
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerFabricEventListener(Event<?> event, Object listener) {
        ((Event) event).register(listener);
    }

    private static Object handleProxyObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CobbleKantoKantoOnlyEggListener";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class || !returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        return null;
    }

    private static Class<?> findListenerTypeWithMethod(Object invoker, String methodName) {
        if (invoker == null) {
            return null;
        }
        for (Class<?> candidate : invoker.getClass().getInterfaces()) {
            for (Method method : candidate.getMethods()) {
                if (method.getName().equals(methodName)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void forceKantoOnlyEggProperties(Object primaryParent, Object secondaryParent, Object pokemonProperties) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled) {
            return;
        }

        String currentSpecies = normalizeSpeciesId(readPokemonPropertiesSpecies(pokemonProperties));
        if (isKantoSpecies(currentSpecies)) {
            return;
        }

        String primarySpecies = normalizeSpeciesId(readPokemonSpeciesId(primaryParent));
        String secondarySpecies = normalizeSpeciesId(readPokemonSpeciesId(secondaryParent));
        String replacementSpecies = KANTO_OFFSPRING_OVERRIDES.get(primarySpecies);
        if (replacementSpecies == null && isKantoSpecies(primarySpecies) && !isBlockedDittoOffspringSpecies(primarySpecies)) {
            // Defensive fallback: if Daycare+/Cobblemon generates a non-#151 pre-evolution
            // that is not in the manual table, keep the main Kanto parent's species.
            // Ditto is intentionally excluded: Ditto may be a parent, but must never
            // become the generated offspring/fallback species.
            replacementSpecies = primarySpecies;
        }
        if (replacementSpecies == null) {
            replacementSpecies = KANTO_OFFSPRING_OVERRIDES.get(secondarySpecies);
        }
        if (replacementSpecies == null || !isKantoSpecies(replacementSpecies)) {
            if (ServerFixesConfig.kantoDaycarePreventDittoOffspring
                    && (DITTO_SPECIES.equals(primarySpecies) || DITTO_SPECIES.equals(secondarySpecies))) {
                // Intentional exception to the old #151-only fallback: when Ditto breeds
                // with a non-Kanto/datapack species, keep Daycare+'s original non-Ditto
                // offspring instead of converting it into a Ditto.
                CobbleKantoServerFixes.LOGGER.debug(
                        "Kanto Daycare: preserving non-Ditto offspring {} for Ditto pairing {} + {}.",
                        currentSpecies,
                        primarySpecies,
                        secondarySpecies
                );
                return;
            }
            CobbleKantoServerFixes.LOGGER.warn(
                    "Daycare+ tried to generate a non-#151 offspring ({}), but no safe Kanto fallback was found. Parents: {} + {}.",
                    currentSpecies,
                    primarySpecies,
                    secondarySpecies
            );
            return;
        }

        invokeIfExists(pokemonProperties, "setSpecies", new Class<?>[]{String.class}, new Object[]{replacementSpecies});
        invokeIfExists(pokemonProperties, "setForm", new Class<?>[]{String.class}, new Object[]{"normal"});
        invokeIfExists(pokemonProperties, "setLevel", new Class<?>[]{Integer.class}, new Object[]{1});
        invokeIfExists(pokemonProperties, "setLevel", new Class<?>[]{int.class}, new Object[]{1});

        String inheritedAbility = readPokemonAbilityName(primaryParent);
        if (inheritedAbility != null && !inheritedAbility.isBlank()) {
            invokeIfExists(pokemonProperties, "setAbility", new Class<?>[]{String.class}, new Object[]{inheritedAbility});
        }

        invokeIfExists(pokemonProperties, "updateAspects", new Class<?>[]{}, new Object[]{});
        CobbleKantoServerFixes.LOGGER.info("Kanto Daycare #151: offspring {} converted to {}.", currentSpecies, replacementSpecies);
    }

    private static boolean isKantoSpecies(String speciesId) {
        return speciesId != null && KANTO_SPECIES.contains(speciesId);
    }

    private static boolean isBlockedDittoOffspringSpecies(String speciesId) {
        return ServerFixesConfig.kantoDaycarePreventDittoOffspring && DITTO_SPECIES.equals(speciesId);
    }

    private static String readPokemonPropertiesSpecies(Object properties) {
        Object value = invokeGetter(properties, "getSpecies");
        if (value == null) {
            value = invokeGetter(properties, "species");
        }
        return value == null ? null : value.toString();
    }

    private static String readPokemonSpeciesId(Object pokemon) {
        Object species = invokeGetter(pokemon, "getSpecies");
        if (species == null) {
            return null;
        }

        Object showdownId = invokeGetter(species, "showdownId");
        if (showdownId != null) {
            return showdownId.toString();
        }
        Object resourceIdentifier = invokeGetter(species, "getResourceIdentifier");
        if (resourceIdentifier != null) {
            return resourceIdentifier.toString();
        }
        Object name = invokeGetter(species, "getName");
        return name == null ? null : name.toString();
    }

    private static String readPokemonAbilityName(Object pokemon) {
        Object ability = invokeGetter(pokemon, "getAbility");
        if (ability == null) {
            return null;
        }
        Object name = invokeGetter(ability, "getName");
        if (name != null) {
            return name.toString();
        }
        Object template = invokeGetter(ability, "getTemplate");
        if (template != null) {
            Object templateName = invokeGetter(template, "getName");
            if (templateName != null) {
                return templateName.toString();
            }
        }
        return null;
    }

    private static Object invokeGetter(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String normalizeSpeciesId(String speciesId) {
        if (speciesId == null) {
            return null;
        }
        String normalized = speciesId.toLowerCase(Locale.ROOT).trim();
        int namespaceIndex = normalized.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < normalized.length()) {
            normalized = normalized.substring(namespaceIndex + 1);
        }
        return normalized.replace("♀", "f")
                .replace("♂", "m")
                .replace(".", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    public static Object getKantoReplacementFormForPotential(Object potentialPokemonProperties) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled || potentialPokemonProperties == null) {
            return null;
        }

        Object currentForm = readFieldIfExists(potentialPokemonProperties, "form");
        String currentSpecies = normalizeSpeciesId(readFormSpeciesId(currentForm));
        if (isKantoSpecies(currentSpecies)) {
            return null;
        }

        Object primary = readFieldIfExists(potentialPokemonProperties, "primary");
        Object secondary = readFieldIfExists(potentialPokemonProperties, "secondary");
        String replacementSpecies = getKantoReplacementSpecies(currentSpecies, primary, secondary);
        if (replacementSpecies == null || !isKantoSpecies(replacementSpecies)) {
            return null;
        }

        Object replacementForm = resolveSpeciesForm(replacementSpecies, "normal");
        if (replacementForm == null) {
            CobbleKantoServerFixes.LOGGER.warn("Kanto Daycare #151: could not resolve replacement form for {} -> {}.", currentSpecies, replacementSpecies);
        }
        return replacementForm;
    }

    public static Object getSpeciesFromForm(Object form) {
        if (form == null) {
            return null;
        }
        return invokeGetter(form, "getSpecies");
    }

    public static void forceKantoOnlyPropertiesForPotential(Object potentialPokemonProperties, Object pokemonProperties) {
        if (potentialPokemonProperties == null || pokemonProperties == null) {
            return;
        }
        Object replacementForm = getKantoReplacementFormForPotential(potentialPokemonProperties);
        if (replacementForm == null) {
            return;
        }

        String speciesId = normalizeSpeciesId(readFormSpeciesId(replacementForm));
        Object formIdObject = invokeNoArg(replacementForm, "formOnlyShowdownId");
        String formId = formIdObject == null || formIdObject.toString().isBlank() ? "normal" : formIdObject.toString();

        invokeIfExists(pokemonProperties, "setSpecies", new Class<?>[]{String.class}, new Object[]{speciesId});
        invokeIfExists(pokemonProperties, "setForm", new Class<?>[]{String.class}, new Object[]{formId});
        invokeIfExists(pokemonProperties, "setLevel", new Class<?>[]{Integer.class}, new Object[]{1});
        invokeIfExists(pokemonProperties, "setLevel", new Class<?>[]{int.class}, new Object[]{1});
        invokeIfExists(pokemonProperties, "updateAspects", new Class<?>[]{}, new Object[]{});
        CobbleKantoServerFixes.LOGGER.info("Kanto Daycare #151: forced final egg properties to {} form {}.", speciesId, formId);
    }

    private static String getKantoReplacementSpecies(String currentSpecies, Object primary, Object secondary) {
        String replacementSpecies = KANTO_OFFSPRING_OVERRIDES.get(currentSpecies);
        if (replacementSpecies != null) {
            return replacementSpecies;
        }

        String primarySpecies = normalizeSpeciesId(readPokemonSpeciesId(primary));
        replacementSpecies = KANTO_OFFSPRING_OVERRIDES.get(primarySpecies);
        if (replacementSpecies != null && !isBlockedDittoOffspringSpecies(replacementSpecies)) {
            return replacementSpecies;
        }
        if (isKantoSpecies(primarySpecies) && !isBlockedDittoOffspringSpecies(primarySpecies)) {
            return primarySpecies;
        }

        String secondarySpecies = normalizeSpeciesId(readPokemonSpeciesId(secondary));
        replacementSpecies = KANTO_OFFSPRING_OVERRIDES.get(secondarySpecies);
        if (replacementSpecies != null && !isBlockedDittoOffspringSpecies(replacementSpecies)) {
            return replacementSpecies;
        }
        if (isKantoSpecies(secondarySpecies) && !isBlockedDittoOffspringSpecies(secondarySpecies)) {
            return secondarySpecies;
        }
        return null;
    }

    private static String readFormSpeciesId(Object form) {
        Object species = invokeGetter(form, "getSpecies");
        if (species == null) {
            return null;
        }
        Object showdownId = invokeNoArg(species, "showdownId");
        if (showdownId != null) {
            return showdownId.toString();
        }
        Object resourceIdentifier = invokeGetter(species, "getResourceIdentifier");
        if (resourceIdentifier != null) {
            return resourceIdentifier.toString();
        }
        Object name = invokeGetter(species, "getName");
        return name == null ? null : name.toString();
    }

    private static Object resolveSpeciesForm(String speciesId, String formId) {
        Object species = getCobblemonSpecies(speciesId);
        if (species == null) {
            return null;
        }
        Object form = invokeOneArg(species, "getFormByShowdownId", String.class, formId == null ? "normal" : formId);
        if (form == null) {
            form = invokeOneArg(species, "getFormByShowdownId", String.class, "normal");
        }
        if (form == null) {
            form = invokeGetter(species, "getStandardForm");
        }
        if (form == null) {
            Object forms = invokeGetter(species, "getForms");
            if (forms instanceof java.util.List<?> list && !list.isEmpty()) {
                return list.getFirst();
            }
        }
        return form;
    }

    private static Object getCobblemonSpecies(String speciesId) {
        try {
            Class<?> pokemonSpecies = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Method getByIdentifier = pokemonSpecies.getMethod("getByIdentifier", Identifier.class);
            return getByIdentifier.invoke(null, Identifier.tryParse("cobblemon:" + speciesId));
        } catch (ReflectiveOperationException exception) {
            CobbleKantoServerFixes.LOGGER.debug("Kanto Daycare #151: failed to find species {}.", speciesId, exception);
            return null;
        }
    }

    private static Object readFieldIfExists(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        return invokeGetter(target, methodName);
    }

    private static Object invokeOneArg(Object target, String methodName, Class<?> argType, Object arg) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName, argType).invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("kantodaycare")
                .requires(source -> source.hasPermissionLevel(ServerFixesConfig.kantoDaycareCommandPermissionLevel))
                .then(CommandManager.literal("open")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            return openForPlayers(context.getSource(), List.of(player));
                        })
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .executes(context -> openForPlayers(context.getSource(), EntityArgumentType.getPlayers(context, "targets")))))
                .then(CommandManager.literal("info")
                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                .executes(context -> sendInfo(context.getSource(), EntityArgumentType.getPlayer(context, "target")))))
                .then(CommandManager.literal("reloadcells")
                        .executes(context -> {
                            loadCellsFromDisk();
                            context.getSource().sendFeedback(() -> Text.literal("KantoDaycare cells reloaded: " + cellsByPlayer.size()), false);
                            return cellsByPlayer.size();
                        }))
                .then(CommandManager.literal("audit")
                        .executes(context -> sendAudit(context.getSource())))
                .then(CommandManager.literal("forceloads")
                        .then(CommandManager.literal("status")
                                .executes(context -> sendForcedChunkStatus(context.getSource())))
                        .then(CommandManager.literal("cleanup")
                                .executes(context -> cleanupForcedChunksCommand(context.getSource()))))
        );
    }

    private static int openForPlayers(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int opened = 0;
        for (ServerPlayerEntity player : players) {
            if (openForPlayer(source, player)) {
                opened++;
            }
        }
        int finalOpened = opened;
        source.sendFeedback(() -> Text.literal("KantoDaycare opened for " + finalOpened + " player(s)."), false);
        return opened;
    }

    private static boolean openForPlayer(ServerCommandSource source, ServerPlayerEntity player) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled) {
            source.sendError(Text.literal("KantoDaycareBridge is disabled in the config."));
            return false;
        }

        if (!World.OVERWORLD.equals(player.getServerWorld().getRegistryKey())) {
            player.sendMessage(Text.literal("Use the Daycare in the main map."), false);
            source.sendError(Text.literal("The player must be in the Overworld to open the technical Daycare."));
            return false;
        }

        if (!isClassAvailable("com.provismet.cobblemon.daycareplus.gui.DaycareGUI")) {
            source.sendError(Text.literal("Daycare+ is not installed/loaded on the server."));
            return false;
        }
        registerKantoOnlyEggHookIfPossible();

        MinecraftServer server = player.getServer();
        if (server == null) {
            source.sendError(Text.literal("Server unavailable."));
            return false;
        }

        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) {
            source.sendError(Text.literal("Overworld unavailable."));
            return false;
        }

        try {
            DaycareCell cell = getOrCreateCell(player);
            BlockPos pos = getCellPos(cell.index());
            keepChunkLoaded(world, pos, player.getUuid());
            ensureTechnicalPasture(world, pos, player);

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity == null) {
                source.sendError(Text.literal("Could not create/find the technical Pasture BlockEntity at " + formatPos(pos) + "."));
                return false;
            }

            configurePasture(blockEntity, player, pos);
            if (ServerFixesConfig.kantoDaycareTeleportToTechnicalCell) {
                startPendingTechnicalOpen(player, pos);
            } else {
                openDaycareGui(blockEntity, player, world.getBlockState(pos), pos);
                player.sendMessage(Text.literal("Daycare opened."), true);
            }
            CobbleKantoServerFixes.LOGGER.info("Opened KantoDaycare for {} at technical pasture {} slot {}.", player.getGameProfile().getName(), formatPos(pos), cell.index());
            return true;
        } catch (Exception exception) {
            source.sendError(Text.literal("Failed to open KantoDaycare: " + exception.getClass().getSimpleName() + " - " + exception.getMessage()));
            CobbleKantoServerFixes.LOGGER.error("Failed to open KantoDaycare for {}.", player.getGameProfile().getName(), exception);
            return false;
        }
    }

    private static int sendInfo(ServerCommandSource source, ServerPlayerEntity player) {
        DaycareCell cell = getOrCreateCell(player);
        BlockPos pos = getCellPos(cell.index());
        ChunkPos chunkPos = new ChunkPos(pos);
        source.sendFeedback(() -> Text.literal(
                "KantoDaycare for " + player.getGameProfile().getName()
                        + ": slot=" + cell.index()
                        + ", pos=" + formatPos(pos)
                        + ", chunk=[" + chunkPos.x + ", " + chunkPos.z + "]"
        ), false);
        return cell.index();
    }

    private static DaycareCell getOrCreateCell(ServerPlayerEntity player) {
        loadCells();
        UUID uuid = player.getUuid();
        DaycareCell existing = cellsByPlayer.get(uuid);
        if (existing != null) {
            return existing;
        }

        int nextIndex = cellsByPlayer.values().stream()
                .map(DaycareCell::index)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
        DaycareCell created = new DaycareCell(uuid, nextIndex, player.getGameProfile().getName());
        cellsByPlayer.put(uuid, created);
        saveCells();
        return created;
    }

    private static BlockPos getCellPos(int index) {
        int layers = getVerticalLayerCount();
        int columnIndex = Math.floorDiv(index, layers);
        int layerIndex = Math.floorMod(index, layers);
        return getColumnLayerPos(columnIndex, layerIndex, getCellSpacingBlocks(), CELL_CENTER_IN_CHUNK);
    }

    private static BlockPos getColumnLayerPos(int columnIndex, int layerIndex, int spacing, int centerOffset) {
        int gridWidth = Math.max(1, ServerFixesConfig.kantoDaycareGridWidthChunks);
        int cellX = Math.floorMod(columnIndex, gridWidth);
        int cellZ = Math.floorDiv(columnIndex, gridWidth);
        int rawX = ServerFixesConfig.kantoDaycareBaseX + cellX * spacing;
        int rawZ = ServerFixesConfig.kantoDaycareBaseZ + cellZ * spacing;
        int chunkAlignedX = Math.floorDiv(rawX, 16) * 16;
        int chunkAlignedZ = Math.floorDiv(rawZ, 16) * 16;
        int y = ServerFixesConfig.kantoDaycareBaseY + layerIndex * getVerticalSpacingBlocks();
        return new BlockPos(chunkAlignedX + centerOffset, y, chunkAlignedZ + centerOffset);
    }

    private static BlockPos getLegacyCellPos(int index) {
        int cellX = Math.floorMod(index, LEGACY_GRID_WIDTH);
        int cellZ = Math.floorDiv(index, LEGACY_GRID_WIDTH);
        return new BlockPos(
                ServerFixesConfig.kantoDaycareBaseX + cellX * LEGACY_CELL_SPACING_BLOCKS + LEGACY_CELL_CENTER_OFFSET,
                ServerFixesConfig.kantoDaycareBaseY,
                ServerFixesConfig.kantoDaycareBaseZ + cellZ * LEGACY_CELL_SPACING_BLOCKS + LEGACY_CELL_CENTER_OFFSET
        );
    }

    private static int getCellSpacingBlocks() {
        int configured = Math.max(16, ServerFixesConfig.kantoDaycareCellSpacing);
        return Math.floorDiv(configured + 15, 16) * 16;
    }

    private static int getVerticalSpacingBlocks() {
        return Math.max(TECHNICAL_CAGE_HEIGHT + 6, ServerFixesConfig.kantoDaycareVerticalSpacing);
    }

    private static int getVerticalLayerCount() {
        int configured = Math.max(1, ServerFixesConfig.kantoDaycareVerticalLayers);
        int spacing = getVerticalSpacingBlocks();
        int highestSafeCenter = 319 - TECHNICAL_CAGE_HEIGHT;
        int available = highestSafeCenter - ServerFixesConfig.kantoDaycareBaseY;
        int safeLayers = available < 0 ? 1 : Math.floorDiv(available, spacing) + 1;
        return Math.max(1, Math.min(configured, safeLayers));
    }

    private static void ensureTechnicalPasture(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        Block pastureBlock = getPastureBlock();
        if (pastureBlock == null) {
            throw new IllegalStateException("Block cobblemon:pasture not found. Is Cobblemon installed?");
        }

        world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        int configuredRadius = Math.max(4, ServerFixesConfig.kantoDaycarePlatformRadius);
        int radius = Math.min(configuredRadius, 6);
        int wallRadius = radius + 1;
        int cageHeight = Math.max(12, TECHNICAL_CAGE_HEIGHT);

        // From this point on, the cell is persistent.
        // Do not clear the inside with AIR on every open. That caused visual resets,
        // broke tests, and could interfere with already configured Pasture/entities.
        for (int dx = -wallRadius; dx <= wallRadius; dx++) {
            for (int dz = -wallRadius; dz <= wallRadius; dz++) {
                BlockPos foundation = pos.add(dx, -2, dz);
                if (!world.getBlockState(foundation).isOf(Blocks.BEDROCK)) {
                    world.setBlockState(foundation, Blocks.BEDROCK.getDefaultState(), 3);
                }

                BlockPos floor = pos.add(dx, -1, dz);
                BlockState floorState = world.getBlockState(floor);
                if (floorState.isAir() || floorState.isOf(Blocks.BEDROCK) || floorState.isOf(Blocks.DIRT) || floorState.isOf(Blocks.GRASS_BLOCK)) {
                    world.setBlockState(floor, Blocks.GRASS_BLOCK.getDefaultState(), 3);
                }

                boolean wallColumn = Math.abs(dx) == wallRadius || Math.abs(dz) == wallRadius;
                for (int dy = 0; dy <= cageHeight; dy++) {
                    BlockPos current = pos.add(dx, dy, dz);
                    boolean roof = dy == cageHeight;
                    boolean pastureSpace = current.equals(pos) || current.equals(pos.up());

                    BlockState currentState = world.getBlockState(current);
                    if ((wallColumn || roof) && !pastureSpace && !currentState.isOf(Blocks.BARRIER)) {
                        world.setBlockState(current, Blocks.BARRIER.getDefaultState(), 3);
                    } else if (!wallColumn && !roof && currentState.isOf(Blocks.BARRIER)) {
                        // Safe migration: older versions could leave a smaller cage/old roof inside
                        // the new area. That could trap/push Pokémon and make Cobblemon
                        // recall/unlink the tether. Remove only internal BARRIER blocks, never normal blocks.
                        world.setBlockState(current, Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
        }

        decorateTechnicalPasture(world, pos, radius, wallRadius);

        BlockState bottom = pastureBlock.getDefaultState();
        bottom = withPropertyValue(bottom, "part", "bottom");
        bottom = withPropertyValue(bottom, "facing", "north");
        bottom = withPropertyValue(bottom, "on", "false");
        bottom = withPropertyValue(bottom, "waterlogged", "false");

        BlockState top = pastureBlock.getDefaultState();
        top = withPropertyValue(top, "part", "top");
        top = withPropertyValue(top, "facing", "north");
        top = withPropertyValue(top, "on", "false");
        top = withPropertyValue(top, "waterlogged", "false");

        // Only create/recreate the Pasture if it is actually missing.
        // If it already exists, never rewrite the blockstate: this preserves BlockEntity, NBT, eggs and tethered Pokémon.
        if (!world.getBlockState(pos).isOf(pastureBlock)) {
            world.setBlockState(pos, bottom, 3);
        }
        if (!world.getBlockState(pos.up()).isOf(pastureBlock)) {
            world.setBlockState(pos.up(), top, 3);
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            configurePasture(blockEntity, player, pos);
        }
    }

    private static void decorateTechnicalPasture(ServerWorld world, BlockPos pos, int innerRadius, int wallRadius) {
        for (int dx = -innerRadius; dx <= innerRadius; dx++) {
            for (int dz = -innerRadius; dz <= innerRadius; dz++) {
                BlockPos ground = pos.add(dx, -1, dz);
                boolean lightTile = Math.abs(dx) == Math.max(2, innerRadius - 2)
                        && Math.abs(dz) == Math.max(2, innerRadius - 2);
                if (lightTile) {
                    if (!world.getBlockState(ground).isOf(Blocks.SEA_LANTERN)) {
                        world.setBlockState(ground, Blocks.SEA_LANTERN.getDefaultState(), 3);
                    }
                    BlockPos aboveLight = ground.up();
                    if (!world.getBlockState(aboveLight).isAir() && !aboveLight.equals(pos)) {
                        world.setBlockState(aboveLight, Blocks.AIR.getDefaultState(), 3);
                    }
                    continue;
                }
                if (!world.getBlockState(ground).isOf(Blocks.GRASS_BLOCK)) {
                    continue;
                }

                BlockPos above = ground.up();
                if (!world.getBlockState(above).isAir()) {
                    continue;
                }
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                if (dx == 0 && dz >= 2 && dz <= wallRadius - 1) {
                    continue;
                }

                int hash = Math.floorMod((dx * 31) + (dz * 17), 29);
                if (hash == 0) {
                    world.setBlockState(above, Blocks.POPPY.getDefaultState(), 3);
                } else if (hash == 1) {
                    world.setBlockState(above, Blocks.DANDELION.getDefaultState(), 3);
                } else if (hash == 2) {
                    world.setBlockState(above, Blocks.AZURE_BLUET.getDefaultState(), 3);
                } else if (hash == 3) {
                    world.setBlockState(above, Blocks.OXEYE_DAISY.getDefaultState(), 3);
                } else if (hash >= 4 && hash <= 8) {
                    world.setBlockState(above, Blocks.SHORT_GRASS.getDefaultState(), 3);
                }
            }
        }
    }

    private static void configurePasture(BlockEntity blockEntity, ServerPlayerEntity player, BlockPos pos) {
        setOwner(blockEntity, player);
        setRoamBounds(blockEntity, pos);
        configureDaycarePlusState(blockEntity, player);
        blockEntity.markDirty();
        if (blockEntity.getWorld() != null) {
            blockEntity.getWorld().updateListeners(pos, blockEntity.getCachedState(), blockEntity.getCachedState(), 3);
        }
    }

    private static void setOwner(Object pastureBlockEntity, ServerPlayerEntity player) {
        boolean ownerIdSet = invokeIfExists(pastureBlockEntity, "setOwnerId", new Class<?>[]{UUID.class}, new Object[]{player.getUuid()});
        if (!ownerIdSet) {
            setFieldIfExists(pastureBlockEntity, "ownerId", player.getUuid());
        }

        boolean ownerNameSet = invokeIfExists(pastureBlockEntity, "setOwnerName", new Class<?>[]{String.class}, new Object[]{player.getGameProfile().getName()});
        if (!ownerNameSet) {
            setFieldIfExists(pastureBlockEntity, "ownerName", player.getGameProfile().getName());
        }
    }

    private static void setRoamBounds(Object pastureBlockEntity, BlockPos pos) {
        // Keep the tether box generous.
        // Cobblemon recalls a Pokémon if it leaves the Tethering box and repositioning fails.
        // The BARRIER cage already contains Pokémon physically; this logical area prevents
        // false recalls caused by large hitboxes, internal teleporting, or small Y/position differences.
        int roamRadius = Math.max(8, ServerFixesConfig.kantoDaycareRoamRadius);
        BlockPos min = pos.add(-roamRadius, -1, -roamRadius);
        BlockPos max = pos.add(roamRadius, TECHNICAL_CAGE_HEIGHT, roamRadius);

        boolean minSet = invokeIfExists(pastureBlockEntity, "setMinRoamPos", new Class<?>[]{BlockPos.class}, new Object[]{min});
        if (!minSet) {
            setFieldIfExists(pastureBlockEntity, "minRoamPos", min);
            setFieldIfExists(pastureBlockEntity, "minTetheringPos", min);
            setFieldIfExists(pastureBlockEntity, "minTetheredPos", min);
            setFieldIfExists(pastureBlockEntity, "minPasturePos", min);
        }

        boolean maxSet = invokeIfExists(pastureBlockEntity, "setMaxRoamPos", new Class<?>[]{BlockPos.class}, new Object[]{max});
        if (!maxSet) {
            setFieldIfExists(pastureBlockEntity, "maxRoamPos", max);
            setFieldIfExists(pastureBlockEntity, "maxTetheringPos", max);
            setFieldIfExists(pastureBlockEntity, "maxTetheredPos", max);
            setFieldIfExists(pastureBlockEntity, "maxPasturePos", max);
        }
    }

    private static void configureDaycarePlusState(Object pastureBlockEntity, ServerPlayerEntity player) {
        Class<?> mixinType = getClassOrNull("com.provismet.cobblemon.daycareplus.imixin.IMixinPastureBlockEntity");
        if (mixinType == null || !mixinType.isInstance(pastureBlockEntity)) {
            throw new IllegalStateException("PastureBlockEntity did not receive the Daycare+ mixin. Check the Daycare+ version.");
        }

        Object mixin = mixinType.cast(pastureBlockEntity);
        UUID breederUuid = readBreederUuid(mixin);
        if (breederUuid == null) {
            breederUuid = UUID.nameUUIDFromBytes(("cobblekanto-daycare:" + player.getUuid()).getBytes(StandardCharsets.UTF_8));
            invokeRequired(mixin, "setBreederUUID", new Class<?>[]{UUID.class}, new Object[]{breederUuid});
        }

        invokeRequired(mixin, "setShouldBreed", new Class<?>[]{boolean.class}, new Object[]{true});
        invokeIfExists(mixin, "setSkipIntroDialogue", new Class<?>[]{boolean.class}, new Object[]{true});
        addBreedingLink(player.getUuid(), breederUuid);
    }

    private static UUID readBreederUuid(Object mixin) {
        try {
            Object value = mixin.getClass().getMethod("getBreederUUID").invoke(mixin);
            if (value instanceof UUID uuid) {
                return uuid;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static void addBreedingLink(UUID playerUuid, UUID breederUuid) {
        try {
            Class<?> breedingLink = Class.forName("com.provismet.cobblemon.daycareplus.breeding.BreedingLink");
            Method has = breedingLink.getMethod("has", UUID.class, UUID.class);
            Object alreadyLinked = has.invoke(null, playerUuid, breederUuid);
            if (alreadyLinked instanceof Boolean linked && linked) {
                return;
            }
            Method add = breedingLink.getMethod("add", UUID.class, UUID.class);
            add.invoke(null, playerUuid, breederUuid);
        } catch (ReflectiveOperationException exception) {
            CobbleKantoServerFixes.LOGGER.debug("Could not pre-register Daycare+ BreedingLink. Daycare+ ticker should recreate it if needed.", exception);
        }
    }

    private static void openDaycareGui(BlockEntity blockEntity, ServerPlayerEntity player, BlockState state, BlockPos pos) throws ReflectiveOperationException {
        Class<?> mixinType = Class.forName("com.provismet.cobblemon.daycareplus.imixin.IMixinPastureBlockEntity");
        Class<?> pastureType = Class.forName("com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity");
        Class<?> guiType = Class.forName("com.provismet.cobblemon.daycareplus.gui.DaycareGUI");

        if (!pastureType.isInstance(blockEntity)) {
            throw new IllegalStateException("Technical BlockEntity is not PokemonPastureBlockEntity: " + blockEntity.getClass().getName());
        }
        if (!mixinType.isInstance(blockEntity)) {
            throw new IllegalStateException("Technical BlockEntity does not implement IMixinPastureBlockEntity.");
        }

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        Method create = guiType.getMethod("create", pastureType, mixinType, ServerPlayerEntity.class, BlockState.class, BlockHitResult.class);
        Object gui = create.invoke(null, pastureType.cast(blockEntity), mixinType.cast(blockEntity), player, state, hit);
        gui.getClass().getMethod("open").invoke(gui);
    }

    private static Block getPastureBlock() {
        if (PASTURE_ID == null) {
            return null;
        }
        if (!Registries.BLOCK.containsId(PASTURE_ID)) {
            return null;
        }
        Block block = Registries.BLOCK.get(PASTURE_ID);
        if (block == Blocks.AIR) {
            return null;
        }
        return block;
    }

    private static BlockState withPropertyValue(BlockState state, String propertyName, String valueName) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(propertyName)) {
                continue;
            }
            Optional<? extends Comparable<?>> value = findPropertyValue(property, valueName);
            if (value.isPresent()) {
                return withRawProperty(state, property, value.get());
            }
        }
        return state;
    }

    private static Optional<? extends Comparable<?>> findPropertyValue(Property<?> property, String valueName) {
        String normalized = valueName.toLowerCase(Locale.ROOT);
        for (Comparable<?> value : property.getValues()) {
            if (value.toString().equalsIgnoreCase(normalized)) {
                return Optional.of(value);
            }
            if (value instanceof StringIdentifiable stringIdentifiable && stringIdentifiable.asString().equalsIgnoreCase(normalized)) {
                return Optional.of(value);
            }
            if (value instanceof Boolean booleanValue && Boolean.toString(booleanValue).equalsIgnoreCase(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withRawProperty(BlockState state, Property property, Comparable value) {
        return state.with(property, value);
    }

    private static void keepChunkLoaded(ServerWorld world, BlockPos pos, UUID playerUuid) {
        ChunkPos chunkPos = new ChunkPos(pos);
        world.getChunkManager().addTicket(daycareChunkTicketType, chunkPos, DAYCARE_TICKET_RADIUS, playerUuid);
        world.getChunk(chunkPos.x, chunkPos.z);
    }

    private static void startPendingTechnicalOpen(ServerPlayerEntity player, BlockPos cellPos) {
        pendingOpens.put(player.getUuid(), new PendingDaycareOpen(player.getUuid(), cellPos, serverTicks, serverTicks + TECHNICAL_GUI_OPEN_DELAY_TICKS));

        Vec3d technicalPos = getTechnicalStandPos(cellPos);
        player.requestTeleport(technicalPos.x, technicalPos.y, technicalPos.z);
        player.setYaw(180.0F);
        player.setPitch(0.0F);
        player.sendMessage(Text.literal("Opening technical Daycare... Use /spawn to return when you are done."), true);
    }

    private static Vec3d getTechnicalStandPos(BlockPos cellPos) {
        return new Vec3d(cellPos.getX() + 0.5D, cellPos.getY(), cellPos.getZ() + 3.5D);
    }

    private static void updatePendingOpens(MinecraftServer server) {
        if (pendingOpens.isEmpty()) {
            return;
        }

        List<UUID> finished = new ArrayList<>();
        for (PendingDaycareOpen pending : List.copyOf(pendingOpens.values())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(pending.playerUuid());
            if (player == null) {
                finished.add(pending.playerUuid());
                continue;
            }

            ServerWorld world = server.getWorld(World.OVERWORLD);
            if (world != null) {
                keepChunkLoaded(world, pending.cellPos(), pending.playerUuid());
            }

            if (serverTicks < pending.openTick()) {
                continue;
            }

            openPendingTechnicalGui(server, player, pending);
            finished.add(pending.playerUuid());
        }

        for (UUID uuid : finished) {
            pendingOpens.remove(uuid);
        }

        pendingOpens.entrySet().removeIf(entry -> serverTicks - entry.getValue().startedTick() > TECHNICAL_PENDING_SESSION_TIMEOUT_TICKS);
    }

    private static void openPendingTechnicalGui(MinecraftServer server, ServerPlayerEntity player, PendingDaycareOpen pending) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) {
            player.sendMessage(Text.literal("Overworld unavailable for opening the technical Daycare."), false);
            return;
        }

        try {
            BlockEntity blockEntity = world.getBlockEntity(pending.cellPos());
            if (blockEntity == null) {
                player.sendMessage(Text.literal("Technical Daycare Pasture not found."), false);
                return;
            }

            configurePasture(blockEntity, player, pending.cellPos());
            openDaycareGui(blockEntity, player, world.getBlockState(pending.cellPos()), pending.cellPos());
            player.sendMessage(Text.literal("Daycare opened. Use /spawn to return when you are done."), true);
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.error("Failed to open pending KantoDaycare GUI for {}.", player.getGameProfile().getName(), exception);
            player.sendMessage(Text.literal("Failed to open the technical Daycare: " + exception.getClass().getSimpleName()), false);
        }
    }

    private static int sendAudit(ServerCommandSource source) {
        int cellCount = cellsByPlayer.size();
        int spacing = getCellSpacingBlocks();
        int gridWidth = Math.max(1, ServerFixesConfig.kantoDaycareGridWidthChunks);
        int layers = getVerticalLayerCount();
        int columns = cellCount == 0 ? 0 : Math.floorDiv(cellCount - 1, layers) + 1;
        int rows = columns == 0 ? 0 : Math.floorDiv(columns - 1, gridWidth) + 1;
        BlockPos first = getCellPos(0);
        BlockPos last = cellCount == 0 ? first : getCellPos(cellCount - 1);
        source.sendFeedback(() -> Text.literal(
                "KantoDaycare audit: cells=" + cellCount
                        + ", spacing=" + spacing + " blocks (" + (spacing / 16) + " chunks)"
                        + ", verticalLayers=" + layers
                        + ", verticalSpacing=" + getVerticalSpacingBlocks()
                        + ", columns=" + columns
                        + ", gridWidth=" + gridWidth
                        + ", rows=" + rows
                        + ", first=" + formatPos(first)
                        + ", last=" + formatPos(last)
                        + ", persistentForceloads=" + countManagedPersistentForcedChunks(source.getServer())
        ), false);
        return cellCount;
    }

    private static int sendForcedChunkStatus(ServerCommandSource source) {
        int count = countManagedPersistentForcedChunks(source.getServer());
        source.sendFeedback(() -> Text.literal("Managed KantoDaycare persistent forceloads found: " + count), false);
        return count;
    }

    private static int cleanupForcedChunksCommand(ServerCommandSource source) {
        int removed = cleanupManagedPersistentForcedChunks(source.getServer());
        source.sendFeedback(() -> Text.literal("Removed " + removed + " managed KantoDaycare persistent forceload(s)."), true);
        return removed;
    }

    private static int countManagedPersistentForcedChunks(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null || cellsByPlayer.isEmpty()) {
            return 0;
        }
        Set<Long> managed = getManagedPersistentChunkKeys();
        int count = 0;
        for (long forced : world.getForcedChunks()) {
            if (managed.contains(forced)) {
                count++;
            }
        }
        return count;
    }

    private static int cleanupManagedPersistentForcedChunks(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null || cellsByPlayer.isEmpty()) {
            return 0;
        }

        Set<Long> managed = getManagedPersistentChunkKeys();
        List<Long> toRemove = new ArrayList<>();
        for (long forced : world.getForcedChunks()) {
            if (managed.contains(forced)) {
                toRemove.add(forced);
            }
        }

        for (long packed : toRemove) {
            ChunkPos chunkPos = new ChunkPos(packed);
            world.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        return toRemove.size();
    }

    private static Set<Long> getManagedPersistentChunkKeys() {
        Set<Long> managed = new java.util.HashSet<>();
        for (DaycareCell cell : cellsByPlayer.values()) {
            managed.add(new ChunkPos(getCellPos(cell.index())).toLong());
            managed.add(new ChunkPos(getLegacyCellPos(cell.index())).toLong());
        }
        return managed;
    }

    private static boolean isClassAvailable(String className) {
        return getClassOrNull(className) != null;
    }

    private static Class<?> getClassOrNull(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static boolean invokeIfExists(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void invokeRequired(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Required method missing: " + methodName, exception);
        }
    }

    private static void setFieldIfExists(Object target, String fieldName, Object value) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException ignored) {
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void loadCells() {
        if (loadedCells) {
            return;
        }
        loadCellsFromDisk();
    }

    private static void loadCellsFromDisk() {
        cellsByPlayer.clear();
        loadedCells = true;
        try {
            Files.createDirectories(CELLS_PATH.getParent());
            if (Files.notExists(CELLS_PATH)) {
                saveCells();
                return;
            }

            List<String> lines = Files.readAllLines(CELLS_PATH, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\t");
                if (parts.length < 2) {
                    continue;
                }
                UUID playerUuid = UUID.fromString(parts[0]);
                int index = Integer.parseInt(parts[1]);
                String lastName = parts.length >= 3 ? parts[2] : "";
                cellsByPlayer.put(playerUuid, new DaycareCell(playerUuid, index, lastName));
            }
            CobbleKantoServerFixes.LOGGER.info("Loaded {} KantoDaycare technical cells from {}.", cellsByPlayer.size(), CELLS_PATH);
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.error("Failed to load KantoDaycare cells from {}. Existing file will not be overwritten in this run.", CELLS_PATH, exception);
        }
    }

    private static void saveCells() {
        try {
            Files.createDirectories(CELLS_PATH.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# player_uuid\tcell_index\tlast_known_name");
            for (DaycareCell cell : cellsByPlayer.values()) {
                lines.add(cell.playerUuid() + "\t" + cell.index() + "\t" + sanitizeCellName(cell.lastKnownName()));
            }
            Files.write(CELLS_PATH, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            CobbleKantoServerFixes.LOGGER.error("Failed to save KantoDaycare cells to {}.", CELLS_PATH, exception);
        }
    }

    private static String sanitizeCellName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record PendingDaycareOpen(UUID playerUuid, BlockPos cellPos, long startedTick, long openTick) {
    }

    private record DaycareCell(UUID playerUuid, int index, String lastKnownName) {
    }

}
