package net.crulim.cobblekantoserverfixes;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Small, isolated client-side hotfixes shipped in the same JAR as the
 * server-side fixes.
 *
 * No networking or server-version coupling is introduced here.
 */
public final class CobbleKantoServerFixesClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobblekanto_server_fixes");

    private static final String COBBLEMON_CARDS_MOD_ID = "cobblemon-cards";
    private static final String BINDER_KEY_TRANSLATION = "key.cobblemon-cards.open_binder";
    private static final String KEYBOARD_B_TRANSLATION = "key.keyboard.b";

    private static final Path KEYBIND_MIGRATION_MARKER = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("cobblekanto_server_fixes")
            .resolve("binder_keybind_migration_v1.done");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded(COBBLEMON_CARDS_MOD_ID)) {
            return;
        }

        ClientLifecycleEvents.CLIENT_STARTED.register(this::migrateCobblemonCardsBinderKeyOnce);
    }

    private void migrateCobblemonCardsBinderKeyOnce(MinecraftClient client) {
        if (Files.isRegularFile(KEYBIND_MIGRATION_MARKER)) {
            return;
        }

        KeyBinding binderKey = findKeyBinding(client, BINDER_KEY_TRANSLATION);
        if (binderKey == null) {
            LOGGER.warn(
                    "Cobblemon Cards is loaded, but its Binder keybinding '{}' was not found. "
                            + "The migration marker was not written so a future launch can retry.",
                    BINDER_KEY_TRANSLATION
            );
            return;
        }

        boolean changed = KEYBOARD_B_TRANSLATION.equals(binderKey.getBoundKeyTranslationKey());

        if (changed) {
            binderKey.setBoundKey(InputUtil.UNKNOWN_KEY);
            KeyBinding.updateKeysByCode();
            client.options.write();

            LOGGER.info(
                    "Unbound Cobblemon Cards Binder from B so existing backpack controls can keep B. "
                            + "This is a one-time client migration; future manual Binder key changes will be preserved."
            );
        } else {
            LOGGER.info(
                    "Cobblemon Cards Binder key was already customized to '{}'; leaving it unchanged.",
                    binderKey.getBoundKeyTranslationKey()
            );
        }

        writeMigrationMarker(changed, binderKey.getBoundKeyTranslationKey());
    }

    private static KeyBinding findKeyBinding(MinecraftClient client, String translationKey) {
        for (KeyBinding keyBinding : client.options.allKeys) {
            if (translationKey.equals(keyBinding.getTranslationKey())) {
                return keyBinding;
            }
        }
        return null;
    }

    private static void writeMigrationMarker(boolean changed, String finalBinding) {
        try {
            Files.createDirectories(KEYBIND_MIGRATION_MARKER.getParent());
            Files.writeString(
                    KEYBIND_MIGRATION_MARKER,
                    "migration=binder_keybind_v1\n"
                            + "changed=" + changed + "\n"
                            + "finalBinding=" + finalBinding + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            LOGGER.warn(
                    "Could not persist Cobblemon Cards Binder keybind migration marker at {}. "
                            + "The keybind itself is still safe for this launch.",
                    KEYBIND_MIGRATION_MARKER,
                    e
            );
        }
    }
}
