package net.crulim.cobblekantoserverfixes;

import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Locale;

public final class DangerousCommandProtector {
    private DangerousCommandProtector() {
    }

    public static boolean shouldBlock(ServerCommandSource source, String command) {
        if (source == null) {
            return false;
        }
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayerEntity player)) {
            return false;
        }
        return shouldBlock(player, command);
    }

    public static boolean shouldBlock(ServerPlayerEntity player, String command) {
        if (!ServerFixesConfig.enabled || !ServerFixesConfig.protectDangerousCommands) {
            return false;
        }
        if (player == null) {
            return false;
        }

        String normalizedCommand = normalizeCommand(command);
        if (normalizedCommand.isEmpty()) {
            return false;
        }

        String blockedRule = findBlockedRule(normalizedCommand);
        if (blockedRule.isEmpty()) {
            return false;
        }

        player.sendMessage(Text.literal("Este comando foi desativado neste servidor."), false);
        CobbleKantoServerFixes.LOGGER.warn(
                "Blocked disabled command '{}' from player {}. Full command='{}'",
                blockedRule,
                player.getGameProfile().getName(),
                normalizedCommand
        );
        return true;
    }

    private static String findBlockedRule(String normalizedCommand) {
        for (String protectedCommand : ServerFixesConfig.protectedCommands) {
            String normalizedProtectedCommand = normalizeCommand(protectedCommand);
            if (normalizedProtectedCommand.isEmpty()) {
                continue;
            }
            if (normalizedCommand.equals(normalizedProtectedCommand) || normalizedCommand.startsWith(normalizedProtectedCommand + " ")) {
                return normalizedProtectedCommand;
            }
        }
        return "";
    }

    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }

        String normalized = command.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }

        normalized = normalized.replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }

        int firstSpaceIndex = normalized.indexOf(' ');
        String root = firstSpaceIndex >= 0 ? normalized.substring(0, firstSpaceIndex) : normalized;
        String tail = firstSpaceIndex >= 0 ? normalized.substring(firstSpaceIndex) : "";

        int namespaceIndex = root.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < root.length()) {
            root = root.substring(namespaceIndex + 1);
        }

        return (root + tail).trim();
    }
}
