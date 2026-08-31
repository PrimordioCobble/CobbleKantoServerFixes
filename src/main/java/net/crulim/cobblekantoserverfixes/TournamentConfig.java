package net.crulim.cobblekantoserverfixes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Small, isolated config for the temporary CobbleKanto tournament bridge.
 *
 * This intentionally lives outside server_fixes.json. The tournament slug/key can be
 * changed between events and /cktournament on reloads this file from disk, so changing
 * tournaments never requires another server restart.
 */
public final class TournamentConfig {
    private static final Path CONFIG_PATH = Path.of("config", "cobblekanto", "tournament.json");

    private static volatile Snapshot current = Snapshot.defaults();

    private TournamentConfig() {
    }

    public static Snapshot current() {
        return current;
    }

    public static synchronized Snapshot reload() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        if (Files.notExists(CONFIG_PATH)) {
            Files.writeString(CONFIG_PATH, defaultConfig(), StandardCharsets.UTF_8);
            CobbleKantoServerFixes.LOGGER.info("Created tournament config at {}.", CONFIG_PATH);
        }

        String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        Snapshot defaults = Snapshot.defaults();
        Snapshot loaded = new Snapshot(
                readString(json, "challongeTournament", defaults.challongeTournament()).trim(),
                readString(json, "challongeApiKey", defaults.challongeApiKey()).trim(),
                clamp(readInt(json, "commandPermissionLevel", defaults.commandPermissionLevel()), 0, 4),
                readBoolean(json, "announceVictories", defaults.announceVictories()),
                readBoolean(json, "logIgnoredResults", defaults.logIgnoredResults()),
                clamp(readInt(json, "httpConnectTimeoutSeconds", defaults.httpConnectTimeoutSeconds()), 2, 20),
                clamp(readInt(json, "httpRequestTimeoutSeconds", defaults.httpRequestTimeoutSeconds()), 3, 30),
                readBoolean(json, "bracketDisplayEnabled", defaults.bracketDisplayEnabled()),
                readString(json, "imgbbApiKey", defaults.imgbbApiKey()).trim(),
                readString(json, "waterframesUpdateCommand", defaults.waterframesUpdateCommand()).trim()
        );
        current = loaded;
        return loaded;
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean readBoolean(String json, String key, boolean fallback) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', keyIndex + key.length() + 2);
        if (colon < 0) {
            return fallback;
        }
        String tail = json.substring(colon + 1).stripLeading().toLowerCase(Locale.ROOT);
        if (tail.startsWith("true")) {
            return true;
        }
        if (tail.startsWith("false")) {
            return false;
        }
        return fallback;
    }

    private static int readInt(String json, String key, int fallback) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', keyIndex + key.length() + 2);
        if (colon < 0) {
            return fallback;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        if (end < json.length() && (json.charAt(end) == '-' || json.charAt(end) == '+')) {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return fallback;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readString(String json, String key, String fallback) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', keyIndex + key.length() + 2);
        if (colon < 0) {
            return fallback;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return fallback;
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    default -> value.append(c);
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return value.toString();
            }
            value.append(c);
        }
        return fallback;
    }

    private static String defaultConfig() {
        return "{\n" +
                "  \"challongeTournament\": \"ly6g72lb\",\n" +
                "  \"challongeApiKey\": \"\",\n" +
                "  \"commandPermissionLevel\": 4,\n" +
                "  \"announceVictories\": true,\n" +
                "  \"logIgnoredResults\": true,\n" +
                "  \"httpConnectTimeoutSeconds\": 5,\n" +
                "  \"httpRequestTimeoutSeconds\": 8,\n" +
                "  \"bracketDisplayEnabled\": false,\n" +
                "  \"imgbbApiKey\": \"\",\n" +
                "  \"waterframesUpdateCommand\": \"execute in minecraft:overworld run waterframes edit 0 0 0 url {url}\"\n" +
                "}\n";
    }

    public record Snapshot(
            String challongeTournament,
            String challongeApiKey,
            int commandPermissionLevel,
            boolean announceVictories,
            boolean logIgnoredResults,
            int httpConnectTimeoutSeconds,
            int httpRequestTimeoutSeconds,
            boolean bracketDisplayEnabled,
            String imgbbApiKey,
            String waterframesUpdateCommand
    ) {
        static Snapshot defaults() {
            return new Snapshot(
                    "ly6g72lb",
                    "",
                    4,
                    true,
                    true,
                    5,
                    8,
                    false,
                    "",
                    "execute in minecraft:overworld run waterframes edit 0 0 0 url {url}"
            );
        }

        public boolean hasCredentials() {
            return challongeTournament != null
                    && !challongeTournament.isBlank()
                    && challongeApiKey != null
                    && !challongeApiKey.isBlank();
        }

        public boolean hasBracketDisplayConfig() {
            return bracketDisplayEnabled
                    && imgbbApiKey != null
                    && !imgbbApiKey.isBlank()
                    && waterframesUpdateCommand != null
                    && waterframesUpdateCommand.contains("{url}");
        }
    }
}
