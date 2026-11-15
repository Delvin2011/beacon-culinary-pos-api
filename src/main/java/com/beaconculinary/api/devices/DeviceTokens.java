package com.beaconculinary.api.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Device tokens: 32 random bytes (256 bits) from SecureRandom, URL-safe Base64 without padding
 * (43 characters). Only the SHA-256 hash is ever stored; comparisons are constant-time. The token
 * itself is never logged.
 */
public final class DeviceTokens {
    static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DeviceTokens() {
    }

    public static String newToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the value looks like a token we issued (cheap check before hashing). */
    public static boolean isWellFormed(String token) {
        return token != null && TOKEN_SHAPE.matcher(token).matches();
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Constant-time comparison of two hashes. */
    public static boolean sameHash(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Serial numbers are compared and stored normalised: all whitespace removed (which also trims),
     * then upper-cased. " ab 12-c " becomes "AB12-C". Blank becomes null.
     */
    public static String normaliseSerial(String raw) {
        if (raw == null) {
            return null;
        }
        var value = WHITESPACE.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    /** Asset tags are only trimmed. Blank becomes null. */
    public static String normaliseAssetTag(String raw) {
        if (raw == null) {
            return null;
        }
        var value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
