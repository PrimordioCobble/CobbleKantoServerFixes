package net.crulim.cobblekantoserverfixes.mixin;

import net.crulim.cobblekantoserverfixes.CobbleKantoServerFixes;
import net.crulim.cobblekantoserverfixes.ServerFixesConfig;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Mixin(targets = "com.provismet.cobblemon.daycareplus.breeding.BreedingUtils", remap = false)
public abstract class KantoDaycareBreedingUtilsMixin {
    private static final Map<String, String> KANTO_BABY_FORM_OVERRIDES = Map.ofEntries(
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


    @Inject(method = "getOffspring", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobblekanto$blockDittoWithDitto(@Coerce Object parent1, @Coerce Object parent2, CallbackInfoReturnable<Object> cir) {
        if (!ServerFixesConfig.enabled
                || !ServerFixesConfig.kantoDaycareBridgeEnabled
                || !ServerFixesConfig.kantoDaycarePreventDittoOffspring
                || parent1 == null
                || parent2 == null) {
            return;
        }

        String parent1Species = normalizeSpeciesId(readPokemonSpeciesId(parent1));
        String parent2Species = normalizeSpeciesId(readPokemonSpeciesId(parent2));
        if (!"ditto".equals(parent1Species) || !"ditto".equals(parent2Species)) {
            return;
        }

        // A Ditto must remain usable as a breeding partner, but Ditto itself must never
        // become a farmable offspring. Returning Optional.empty() here makes a Ditto +
        // Ditto pair incompatible before Daycare+ can create preview/egg properties.
        cir.setReturnValue(Optional.empty());
        CobbleKantoServerFixes.LOGGER.info("Kanto Daycare: blocked Ditto + Ditto breeding to prevent Ditto offspring generation.");
    }

    @Inject(method = "getBabyForm", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobblekanto$forceKantoBabyForm(@Coerce Object parent, CallbackInfoReturnable<Object> cir) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.kantoDaycareBridgeEnabled || parent == null) {
            return;
        }

        String parentSpecies = normalizeSpeciesId(readPokemonSpeciesId(parent));
        String replacementSpecies = KANTO_BABY_FORM_OVERRIDES.get(parentSpecies);
        if (replacementSpecies == null) {
            return;
        }

        Object replacementForm = resolveReplacementForm(parent, parentSpecies, replacementSpecies);
        if (replacementForm == null) {
            CobbleKantoServerFixes.LOGGER.warn("Kanto Daycare #151: could not resolve FormData for {} -> {}.", parentSpecies, replacementSpecies);
            return;
        }

        cir.setReturnValue(replacementForm);
    }

    private static Object resolveReplacementForm(Object parent, String parentSpecies, String replacementSpecies) {
        Object parentForm = invokeGetter(parent, "getForm");
        Object species = getCobblemonSpecies(replacementSpecies);
        if (species == null) {
            return null;
        }

        String formId = "normal";
        Object parentFormId = invokeNoArg(parentForm, "formOnlyShowdownId");
        if (parentFormId != null && !parentFormId.toString().isBlank()) {
            formId = parentFormId.toString();
        }

        Object form = invokeOneArg(species, "getFormByShowdownId", String.class, formId);
        if (form == null && !"normal".equals(formId)) {
            form = invokeOneArg(species, "getFormByShowdownId", String.class, "normal");
        }
        if (form == null) {
            form = invokeGetter(species, "getStandardForm");
        }
        if (form == null) {
            form = invokeGetter(species, "getForms");
            if (form instanceof java.util.List<?> list && !list.isEmpty()) {
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

    private static String readPokemonSpeciesId(Object pokemon) {
        Object species = invokeGetter(pokemon, "getSpecies");
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

    private static Object invokeGetter(Object target, String methodName) {
        return invokeNoArg(target, methodName);
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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
}
