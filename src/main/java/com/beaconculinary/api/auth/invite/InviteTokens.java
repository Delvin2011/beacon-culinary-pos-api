package com.beaconculinary.api.auth.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * T0-6a: invite tokens. A token is 256 random bits, Base64url without padding (43 characters).
 * Only its SHA-256 (lowercase hex) is stored; the token is shown once and never stored or logged.
 *
 * <p>Plain Java with no Spring dependency: the first-admin command uses it too.
 */
public final class InviteTokens {
    public static final int TOKEN_BYTES = 32;
    public static final int TOKEN_LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}");

    private InviteTokens() {
    }

    /** A new random token. */
    public static String newToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form: SHA-256 of the token's characters, lowercase hex (64 characters). */
    public static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Whether the text has a token's shape; anything else is refused without a lookup. */
    public static boolean wellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }
}
