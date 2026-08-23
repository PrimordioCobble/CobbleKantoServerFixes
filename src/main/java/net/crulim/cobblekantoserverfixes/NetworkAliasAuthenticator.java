package net.crulim.cobblekantoserverfixes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Verifies UUID-bound, short-lived proxy alias instructions. */
final class NetworkAliasAuthenticator {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_SECONDS = 120;
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private NetworkAliasAuthenticator() {
    }

    static boolean hasValidSecret(String secret) {
        return secret != null && secret.length() >= MINIMUM_SECRET_LENGTH;
    }

    static Optional<String> verify(UUID playerId, String payload, String secret) {
        if (!hasValidSecret(secret) || payload == null) {
            return Optional.empty();
        }

        String[] parts = payload.split("\\|", 4);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }

        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - issuedAt) > MAX_CLOCK_SKEW_SECONDS) {
            return Optional.empty();
        }

        String instruction = parts[2];
        byte[] suppliedSignature;
        try {
            suppliedSignature = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        String body = signingBody(playerId, issuedAt, instruction);
        byte[] expectedSignature = hmac(secret, body);
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            return Optional.empty();
        }

        return Optional.of(instruction);
    }

    private static String signingBody(UUID playerId, long issuedAt, String instruction) {
        return VERSION + "\n" + playerId + "\n" + issuedAt + "\n" + instruction;
    }

    private static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not verify network alias authentication signature", exception);
        }
    }
}
