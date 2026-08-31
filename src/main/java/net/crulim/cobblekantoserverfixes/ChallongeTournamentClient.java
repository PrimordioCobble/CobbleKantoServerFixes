package net.crulim.cobblekantoserverfixes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Challonge API v2.1 client used by the tournament bridge.
 *
 * It intentionally uses only JDK classes. No new library/mod dependency is introduced.
 * Network calls are expected to run on TournamentBattleBridge's background executor and
 * never on Minecraft's server thread.
 */
public final class ChallongeTournamentClient {
    private static final String DEFAULT_API_BASE = "https://api.challonge.com/v2.1";

    private final TournamentConfig.Snapshot config;
    private final String apiBase;
    private final HttpClient http;
    private volatile Map<String, Participant> participantsByNormalizedName = Map.of();
    private volatile String tournamentName = "";
    private volatile String tournamentType = "";

    public ChallongeTournamentClient(TournamentConfig.Snapshot config) {
        this(config, DEFAULT_API_BASE);
    }

    // Package-private for deterministic local integration tests; production always uses DEFAULT_API_BASE.
    ChallongeTournamentClient(TournamentConfig.Snapshot config, String apiBase) {
        this.config = Objects.requireNonNull(config, "config");
        String normalizedBase = Objects.requireNonNull(apiBase, "apiBase").trim();
        if (!normalizedBase.startsWith("http://") && !normalizedBase.startsWith("https://")) {
            throw new IllegalArgumentException("Challonge API base must be HTTP(S).");
        }
        this.apiBase = normalizedBase.endsWith("/")
                ? normalizedBase.substring(0, normalizedBase.length() - 1)
                : normalizedBase;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized Validation validateAndLoad() throws IOException, InterruptedException {
        Map<String, Object> tournamentRoot = asObject(parseJson(get(tournamentPath())));
        Map<String, Object> tournamentData = asObject(tournamentRoot.get("data"));
        Map<String, Object> tournamentAttributes = asObject(tournamentData.get("attributes"));

        tournamentName = stringValue(tournamentAttributes.get("name"));
        tournamentType = stringValue(tournamentAttributes.get("tournament_type"));

        Map<String, Participant> loadedParticipants = loadParticipants();
        List<OpenMatch> openMatches = loadOpenMatches();
        participantsByNormalizedName = loadedParticipants;

        return new Validation(
                tournamentName,
                tournamentType,
                loadedParticipants.size(),
                openMatches.size()
        );
    }

    public synchronized ReportResult reportWinner(String winnerMinecraftName, String loserMinecraftName)
            throws IOException, InterruptedException {
        String winnerKey = normalizeName(winnerMinecraftName);
        String loserKey = normalizeName(loserMinecraftName);

        Participant winner = participantsByNormalizedName.get(winnerKey);
        Participant loser = participantsByNormalizedName.get(loserKey);

        // Participants can be edited on Challonge while the toggle is ON. Refresh once
        // automatically instead of requiring an organizer-side /sync command.
        if (winner == null || loser == null) {
            participantsByNormalizedName = loadParticipants();
            winner = participantsByNormalizedName.get(winnerKey);
            loser = participantsByNormalizedName.get(loserKey);
        }

        if (winner == null || loser == null) {
            return ReportResult.ignored(
                    "participant-not-found",
                    "No Challonge participant matched " + winnerMinecraftName + " vs " + loserMinecraftName + "."
            );
        }
        if (winner.id().equals(loser.id())) {
            return ReportResult.ignored("same-participant", "Winner and loser resolved to the same Challonge participant.");
        }

        final String winnerId = winner.id();
        final String loserId = loser.id();

        List<OpenMatch> openMatches = loadOpenMatches();
        List<OpenMatch> matching = openMatches.stream()
                .filter(match -> match.containsExactly(winnerId, loserId))
                .toList();

        if (matching.isEmpty()) {
            return ReportResult.ignored(
                    "no-open-match",
                    "No open Challonge match exists for " + winner.name() + " vs " + loser.name() + "."
            );
        }
        if (matching.size() != 1) {
            return ReportResult.ignored(
                    "ambiguous-open-match",
                    "More than one open Challonge match matched the same participant pair; refusing to guess."
            );
        }

        OpenMatch match = matching.get(0);
        String payload = buildWinnerPayload(winnerId, loserId);
        try {
            String responseBody = put(matchPath(match.id()), payload);
            verifyUpdatedMatch(responseBody, match.id(), winnerId);
            return ReportResult.updated(match.id(), match.identifier(), winner.name(), loser.name());
        } catch (IOException updateFailure) {
            // A timeout can happen after Challonge committed the score but before the response reached us.
            // Confirm the exact match before treating the update as failed, preventing a blind duplicate PUT.
            try {
                if (matchHasWinner(match.id(), winnerId)) {
                    return ReportResult.updated(match.id(), match.identifier(), winner.name(), loser.name());
                }
            } catch (IOException confirmationFailure) {
                updateFailure.addSuppressed(confirmationFailure);
            }
            throw updateFailure;
        }
    }

    public String tournamentName() {
        return tournamentName;
    }

    public String tournamentType() {
        return tournamentType;
    }

    public int cachedParticipantCount() {
        return participantsByNormalizedName.size();
    }

    public synchronized SubstituteResult substituteParticipant(String currentParticipantName, String replacementMinecraftName)
            throws IOException, InterruptedException {
        String currentKey = normalizeName(currentParticipantName);
        String replacementKey = normalizeName(replacementMinecraftName);
        if (currentKey.isBlank() || replacementKey.isBlank()) {
            throw new IllegalArgumentException("Participant names cannot be blank.");
        }

        participantsByNormalizedName = loadParticipants();
        Participant current = participantsByNormalizedName.get(currentKey);
        if (current == null) {
            return SubstituteResult.ignored(
                    "participant-not-found",
                    "No Challonge participant matched '" + currentParticipantName + "'."
            );
        }

        Participant existingReplacement = participantsByNormalizedName.get(replacementKey);
        if (existingReplacement != null && !existingReplacement.id().equals(current.id())) {
            return SubstituteResult.ignored(
                    "replacement-already-present",
                    "'" + replacementMinecraftName + "' already exists as another Challonge participant."
            );
        }
        if (normalizeName(current.name()).equals(replacementKey)) {
            return SubstituteResult.ignored(
                    "same-name",
                    "The participant is already named '" + replacementMinecraftName + "'."
            );
        }

        String responseBody = put(participantPath(current.id()), buildParticipantNamePayload(replacementMinecraftName));
        verifyUpdatedParticipant(responseBody, current.id(), replacementMinecraftName);
        participantsByNormalizedName = loadParticipants();

        Participant replacement = participantsByNormalizedName.get(replacementKey);
        if (replacement == null || !replacement.id().equals(current.id())) {
            throw new IOException("Challonge updated the participant but the refreshed participant cache did not confirm it.");
        }
        return SubstituteResult.updated(current.id(), current.name(), replacement.name());
    }

    public synchronized BracketSnapshot loadBracketSnapshot() throws IOException, InterruptedException {
        Map<String, Participant> byName = loadParticipants();
        Map<String, String> participantsById = new LinkedHashMap<>();
        for (Participant participant : byName.values()) {
            participantsById.put(participant.id(), participant.name());
        }

        Map<String, Object> root = asObject(parseJson(get(allMatchesPath())));
        List<Object> data = asArray(root.get("data"));
        List<BracketMatch> matches = new ArrayList<>();

        for (Object entry : data) {
            Map<String, Object> matchObject = asObject(entry);
            String id = stringValue(matchObject.get("id"));
            Map<String, Object> attributes = asObject(matchObject.get("attributes"));
            if (id.isBlank()) {
                continue;
            }
            List<String> participantIds = bracketParticipantIds(matchObject, attributes);
            matches.add(new BracketMatch(
                    id,
                    stringValue(attributes.get("identifier")),
                    stringValue(attributes.get("state")),
                    intValue(attributes.get("round"), 0),
                    intValue(attributes.get("suggested_play_order"), Integer.MAX_VALUE),
                    participantIds,
                    stringValue(attributes.get("winner_id")),
                    stringValue(attributes.get("scores"))
            ));
        }

        return new BracketSnapshot(
                fallback(tournamentName, config.challongeTournament()),
                tournamentType,
                Collections.unmodifiableMap(participantsById),
                List.copyOf(matches)
        );
    }

    private static List<String> bracketParticipantIds(Map<String, Object> matchObject, Map<String, Object> attributes)
            throws IOException {
        List<String> ids = new ArrayList<>(2);
        for (Object value : asArray(attributes.get("points_by_participant"))) {
            Map<String, Object> point = asObject(value);
            String participantId = stringValue(point.get("participant_id"));
            if (!participantId.isBlank() && !ids.contains(participantId)) {
                ids.add(participantId);
            }
        }
        if (!ids.isEmpty()) {
            return List.copyOf(ids);
        }

        Map<String, Object> relationships = asObject(matchObject.get("relationships"));
        String player1Id = relationshipParticipantId(relationships.get("player1"));
        String player2Id = relationshipParticipantId(relationships.get("player2"));
        if (!player1Id.isBlank()) {
            ids.add(player1Id);
        }
        if (!player2Id.isBlank() && !ids.contains(player2Id)) {
            ids.add(player2Id);
        }
        return List.copyOf(ids);
    }

    private Map<String, Participant> loadParticipants() throws IOException, InterruptedException {
        Map<String, Object> root = asObject(parseJson(get(participantsPath())));
        List<Object> data = asArray(root.get("data"));
        Map<String, Participant> loaded = new LinkedHashMap<>();

        for (Object entry : data) {
            Map<String, Object> participantObject = asObject(entry);
            String id = stringValue(participantObject.get("id"));
            Map<String, Object> attributes = asObject(participantObject.get("attributes"));
            String name = stringValue(attributes.get("name"));
            if (id.isBlank() || name.isBlank()) {
                continue;
            }

            String normalized = normalizeName(name);
            Participant previous = loaded.putIfAbsent(normalized, new Participant(id, name));
            if (previous != null && !previous.id().equals(id)) {
                throw new IOException(
                        "Ambiguous Challonge participant names after case-insensitive normalization: '"
                                + previous.name() + "' and '" + name + "'."
                );
            }
        }
        return Collections.unmodifiableMap(loaded);
    }

    private List<OpenMatch> loadOpenMatches() throws IOException, InterruptedException {
        Map<String, Object> root = asObject(parseJson(get(matchesPath())));
        List<Object> data = asArray(root.get("data"));
        List<OpenMatch> matches = new ArrayList<>();

        for (Object entry : data) {
            Map<String, Object> matchObject = asObject(entry);
            String id = stringValue(matchObject.get("id"));
            Map<String, Object> attributes = asObject(matchObject.get("attributes"));
            String state = stringValue(attributes.get("state"));
            // Fail closed if Challonge ever changes/omits match state. Only explicit OPEN matches
            // are eligible to receive a tournament result.
            if (!"open".equalsIgnoreCase(state)) {
                continue;
            }
            String identifier = stringValue(attributes.get("identifier"));
            List<String> participantIds = matchParticipantIds(matchObject, attributes);
            if (id.isBlank() || participantIds.size() != 2) {
                continue;
            }
            matches.add(new OpenMatch(id, identifier, participantIds.get(0), participantIds.get(1)));
        }
        return List.copyOf(matches);
    }

    private static List<String> matchParticipantIds(Map<String, Object> matchObject, Map<String, Object> attributes)
            throws IOException {
        // Challonge v2.1 currently exposes the two participants of an open match in
        // attributes.points_by_participant. Older/alternate responses may expose player1/player2
        // through top-level relationships, so keep that as a compatibility fallback.
        List<String> ids = new ArrayList<>(2);
        for (Object value : asArray(attributes.get("points_by_participant"))) {
            Map<String, Object> point = asObject(value);
            String participantId = stringValue(point.get("participant_id"));
            if (!participantId.isBlank() && !ids.contains(participantId)) {
                ids.add(participantId);
            }
        }
        if (ids.size() == 2) {
            return List.copyOf(ids);
        }

        Map<String, Object> relationships = asObject(matchObject.get("relationships"));
        String player1Id = relationshipParticipantId(relationships.get("player1"));
        String player2Id = relationshipParticipantId(relationships.get("player2"));
        if (!player1Id.isBlank() && !player2Id.isBlank() && !player1Id.equals(player2Id)) {
            return List.of(player1Id, player2Id);
        }
        return List.of();
    }

    private static String relationshipParticipantId(Object relationshipValue) throws IOException {
        Map<String, Object> relationship = asObject(relationshipValue);
        Map<String, Object> data = asObject(relationship.get("data"));
        return stringValue(data.get("id"));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(path).GET().build();
        return send(request);
    }

    private String put(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(path)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private void verifyUpdatedMatch(String responseBody, String expectedMatchId, String expectedWinnerId)
            throws IOException {
        Map<String, Object> responseRoot = asObject(parseJson(responseBody));
        Map<String, Object> responseData = asObject(responseRoot.get("data"));
        String returnedId = stringValue(responseData.get("id"));
        Map<String, Object> attributes = asObject(responseData.get("attributes"));
        String returnedWinnerId = stringValue(attributes.get("winner_id"));
        String state = stringValue(attributes.get("state"));

        if (!expectedMatchId.equals(returnedId)) {
            throw new IOException("Challonge returned an unexpected match id after update.");
        }
        if (!expectedWinnerId.equals(returnedWinnerId)) {
            throw new IOException("Challonge did not confirm the expected winner after update.");
        }
        if (!"complete".equalsIgnoreCase(state)) {
            throw new IOException("Challonge did not mark the updated match complete.");
        }
    }

    private void verifyUpdatedParticipant(String responseBody, String expectedParticipantId, String expectedName)
            throws IOException {
        Map<String, Object> responseRoot = asObject(parseJson(responseBody));
        Map<String, Object> responseData = asObject(responseRoot.get("data"));
        String returnedId = stringValue(responseData.get("id"));
        Map<String, Object> attributes = asObject(responseData.get("attributes"));
        String returnedName = stringValue(attributes.get("name"));

        if (!expectedParticipantId.equals(returnedId)) {
            throw new IOException("Challonge returned an unexpected participant id after substitution.");
        }
        if (!normalizeName(expectedName).equals(normalizeName(returnedName))) {
            throw new IOException("Challonge did not confirm the replacement participant name.");
        }
    }

    private boolean matchHasWinner(String matchId, String expectedWinnerId) throws IOException, InterruptedException {
        Map<String, Object> root = asObject(parseJson(get(matchPath(matchId))));
        Map<String, Object> data = asObject(root.get("data"));
        if (!matchId.equals(stringValue(data.get("id")))) {
            return false;
        }
        Map<String, Object> attributes = asObject(data.get("attributes"));
        return expectedWinnerId.equals(stringValue(attributes.get("winner_id")))
                && "complete".equalsIgnoreCase(stringValue(attributes.get("state")));
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(config.httpRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/vnd.api+json")
                // Challonge API v2.1 explicitly supports legacy API-v1 keys through these headers.
                .header("Authorization-Type", "v1")
                .header("Authorization", config.challongeApiKey());
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String compactBody = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            if (compactBody.length() > 300) {
                compactBody = compactBody.substring(0, 300) + "...";
            }
            throw new IOException("Challonge HTTP " + status + (compactBody.isBlank() ? "" : ": " + compactBody));
        }
        return response.body() == null ? "" : response.body();
    }

    private String tournamentPath() {
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament()) + ".json";
    }

    private String participantsPath() {
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament()) + "/participants.json?per_page=100";
    }

    private String matchesPath() {
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament()) + "/matches.json?state=open&per_page=100";
    }

    private String allMatchesPath() {
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament()) + "/matches.json?per_page=100";
    }

    private String participantPath(String participantId) {
        if (participantId == null || !participantId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Unsafe Challonge participant id.");
        }
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament())
                + "/participants/" + participantId + ".json";
    }

    private String matchPath(String matchId) {
        if (matchId == null || !matchId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Unsafe Challonge match id.");
        }
        return "/tournaments/" + validateTournamentIdentifier(config.challongeTournament()) + "/matches/" + matchId + ".json";
    }

    private static String validateTournamentIdentifier(String value) {
        String trimmed = value == null ? "" : value.trim();
        // Challonge slugs are URL-safe identifiers. Refuse arbitrary path/query content because
        // this value comes from a local config file and is concatenated into an API URI.
        if (!trimmed.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Challonge tournament slug/id: " + trimmed);
        }
        return trimmed;
    }

    private static String buildParticipantNamePayload(String name) {
        return "{\"data\":{\"type\":\"participant\",\"attributes\":{\"name\":\""
                + jsonEscape(name) + "\"}}}";
    }

    private static String buildWinnerPayload(String winnerId, String loserId) {
        return "{\"data\":{\"type\":\"match\",\"attributes\":{\"match\":["
                + "{\"participant_id\":\"" + jsonEscape(winnerId) + "\",\"score_set\":\"1\",\"advancing\":true},"
                + "{\"participant_id\":\"" + jsonEscape(loserId) + "\",\"score_set\":\"0\"}"
                + "],\"tie\":false}}}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value == null) {
            return Map.of();
        }
        throw new IOException("Expected JSON object but received " + value.getClass().getSimpleName() + ".");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value) throws IOException {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (value == null) {
            return List.of();
        }
        throw new IOException("Expected JSON array but received " + value.getClass().getSimpleName() + ".");
    }

    static Object parseJson(String json) throws IOException {
        return new JsonParser(json == null ? "" : json).parse();
    }

    public record Validation(String tournamentName, String tournamentType, int participantCount, int openMatchCount) {
    }

    public record ReportResult(
            Status status,
            String reason,
            String detail,
            String matchId,
            String matchIdentifier,
            String winnerName,
            String loserName
    ) {
        public static ReportResult ignored(String reason, String detail) {
            return new ReportResult(Status.IGNORED, reason, detail, "", "", "", "");
        }

        public static ReportResult updated(String matchId, String identifier, String winnerName, String loserName) {
            return new ReportResult(Status.UPDATED, "", "", matchId, identifier, winnerName, loserName);
        }
    }

    public record SubstituteResult(
            Status status,
            String reason,
            String detail,
            String participantId,
            String previousName,
            String replacementName
    ) {
        public static SubstituteResult ignored(String reason, String detail) {
            return new SubstituteResult(Status.IGNORED, reason, detail, "", "", "");
        }

        public static SubstituteResult updated(String participantId, String previousName, String replacementName) {
            return new SubstituteResult(Status.UPDATED, "", "", participantId, previousName, replacementName);
        }
    }

    public record BracketSnapshot(
            String tournamentName,
            String tournamentType,
            Map<String, String> participantsById,
            List<BracketMatch> matches
    ) {
    }

    public record BracketMatch(
            String id,
            String identifier,
            String state,
            int round,
            int suggestedPlayOrder,
            List<String> participantIds,
            String winnerId,
            String scores
    ) {
    }

    public enum Status {
        UPDATED,
        IGNORED
    }

    private record Participant(String id, String name) {
    }

    private record OpenMatch(String id, String identifier, String player1Id, String player2Id) {
        boolean containsExactly(String firstId, String secondId) {
            return (player1Id.equals(firstId) && player2Id.equals(secondId))
                    || (player1Id.equals(secondId) && player2Id.equals(firstId));
        }
    }

    /** Tiny standards-compliant JSON reader to avoid introducing a new dependency. */
    private static final class JsonParser {
        private final String input;
        private int index;

        private JsonParser(String input) {
            this.input = input;
        }

        Object parse() throws IOException {
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (index != input.length()) {
                throw error("Unexpected trailing JSON content");
            }
            return value;
        }

        private Object readValue() throws IOException {
            skipWhitespace();
            if (index >= input.length()) {
                throw error("Unexpected end of JSON");
            }
            return switch (input.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() throws IOException {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("Expected object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> readArray() throws IOException {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return result.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("Unescaped control character in JSON string");
                    }
                    result.append(c);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("Incomplete JSON escape");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicodeEscape());
                    default -> throw error("Invalid JSON escape: \\" + escaped);
                }
            }
            throw error("Unterminated JSON string");
        }

        private char readUnicodeEscape() throws IOException {
            if (index + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Object readNumber() throws IOException {
            int start = index;
            if (peek('-')) {
                index++;
            }
            readDigits();
            boolean fractional = false;
            if (peek('.')) {
                fractional = true;
                index++;
                readDigits();
            }
            if (peek('e') || peek('E')) {
                fractional = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                readDigits();
            }
            if (index == start) {
                throw error("Invalid JSON value");
            }
            String raw = input.substring(start, index);
            try {
                if (fractional) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw error("Invalid JSON number");
            }
        }

        private void readDigits() throws IOException {
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw error("Expected digit");
            }
        }

        private Object readLiteral(String literal, Object value) throws IOException {
            if (!input.startsWith(literal, index)) {
                throw error("Invalid JSON literal");
            }
            index += literal.length();
            return value;
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IOException error(String message) {
            return new IOException(message + " at JSON offset " + index + ".");
        }
    }
}
