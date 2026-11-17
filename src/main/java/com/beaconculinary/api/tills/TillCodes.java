package com.beaconculinary.api.tills;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * O2 R1: till codes. A new till gets the lowest free {@code T<n>} among active tills (T1, T2, …); an
 * admin may set any code of 1–10 characters from A–Z, 0–9 and '-'. Codes are stored upper-cased.
 */
public final class TillCodes {
    public static final int MAX_LENGTH = 10;
    private static final Pattern VALID = Pattern.compile("[A-Z0-9-]{1," + MAX_LENGTH + "}");

    private TillCodes() {
    }

    /** Trimmed and upper-cased; null stays null. */
    public static String normalise(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Whether a normalised code is allowed. */
    public static boolean isValid(String code) {
        return code != null && VALID.matcher(code).matches();
    }

    /** The lowest T1, T2, … not in {@code taken} (the active tills' codes). Codes that aren't T-numbers never block one. */
    public static String lowestFree(Collection<String> taken) {
        var used = new HashSet<String>();
        for (var code : taken) {
            if (code != null) {
                used.add(normalise(code));
            }
        }
        for (int n = 1; ; n++) {
            var candidate = "T" + n;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }
}
