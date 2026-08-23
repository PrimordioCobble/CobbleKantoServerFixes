package net.crulim.cobblekantoserverfixes;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;

import java.util.Locale;

public final class HuntsItemBridge {
    private HuntsItemBridge() {
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);

            if (world.isClient()) {
                return TypedActionResult.pass(stack);
            }
            if (!ServerFixesConfig.enabled || !ServerFixesConfig.huntsItemBridgeEnabled) {
                return TypedActionResult.pass(stack);
            }
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return TypedActionResult.pass(stack);
            }
            if (stack.isEmpty()) {
                return TypedActionResult.pass(stack);
            }

            String heldItemId = Registries.ITEM.getId(stack.getItem()).toString().toLowerCase(Locale.ROOT);
            String configuredItemId = ServerFixesConfig.huntsItemId == null
                    ? ""
                    : ServerFixesConfig.huntsItemId.trim().toLowerCase(Locale.ROOT);
            if (!heldItemId.equals(configuredItemId)) {
                return TypedActionResult.pass(stack);
            }

            if (ServerFixesConfig.huntsItemCooldownTicks > 0
                    && serverPlayer.getItemCooldownManager().isCoolingDown(stack.getItem())) {
                return TypedActionResult.fail(stack);
            }

            boolean executed = executeHuntsCommand(serverPlayer);
            if (executed && ServerFixesConfig.huntsItemCooldownTicks > 0) {
                serverPlayer.getItemCooldownManager().set(stack.getItem(), ServerFixesConfig.huntsItemCooldownTicks);
            }

            return executed ? TypedActionResult.success(stack) : TypedActionResult.fail(stack);
        });
    }

    private static boolean executeHuntsCommand(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        String command = ServerFixesConfig.huntsCommand == null ? "hunts" : ServerFixesConfig.huntsCommand.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            player.sendMessage(Text.literal("O comando do item de Hunts não está configurado."), false);
            return false;
        }

        try {
            server.getCommandManager().executeWithPrefix(player.getCommandSource(), "/" + command);
            return true;
        } catch (Exception exception) {
            CobbleKantoServerFixes.LOGGER.error(
                    "Failed to execute Hunts command '{}' for player {}.",
                    command,
                    player.getGameProfile().getName(),
                    exception
            );
            player.sendMessage(Text.literal("Não foi possível abrir as Hunts agora."), false);
            return false;
        }
    }
}
