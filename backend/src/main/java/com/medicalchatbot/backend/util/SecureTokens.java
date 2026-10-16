package com.medicalchatbot.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Primitives for secrets kept in Redis (refresh tokens, password-reset codes and tickets).
 *
 * <p>Only SHA-256 hashes are stored and comparisons are constant-time, so a Redis leak does not
 * reveal usable tokens.</p>
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private SecureTokens() {
    }

    /** URL-safe random secret with 256 bits of entropy. */
    public static String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Uniformly random numeric code with exactly {@code length} digits (leading zeros kept). */
    public static String randomDigits(int length) {
        int bound = (int) Math.pow(10, length);
        return String.format("%0" + length + "d", RANDOM.nextInt(bound));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Opaque token handed to clients as {@code {id}.{secret}}: the id is the Redis lookup key and
     * only the hash of the secret is stored.
     */
    public record OpaqueToken(String id, String secret) {

        public static OpaqueToken generate() {
            return new OpaqueToken(UUID.randomUUID().toString(), randomSecret());
        }

        public static Optional<OpaqueToken> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            int dot = raw.indexOf('.');
            if (dot <= 0 || dot == raw.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new OpaqueToken(raw.substring(0, dot), raw.substring(dot + 1)));
        }

        public String value() {
            return id + "." + secret;
        }

        public String secretHash() {
            return sha256Hex(secret);
        }

        public boolean matches(String storedSecretHash) {
            return constantTimeEquals(secretHash(), storedSecretHash);
        }
    }
}
