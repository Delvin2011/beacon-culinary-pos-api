package com.beaconculinary.api.tenancy.secrets;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T1-1: the rule for secret names, and how a name becomes an environment variable.
 *
 * <p>Names follow Azure Key Vault's rule, kept to lower case so a name is written one way only: lower-case letters and
 * digits in groups joined by single hyphens, 1 to 127 characters, e.g. {@code beaconculinary-db-password}. The same
 * names are used in Key Vault (T1-0b), so moving a secret there changes no stored name.
 *
 * <p>Environment variable: {@code SECRET_} followed by the name in upper case with hyphens as underscores, e.g.
 * {@code beaconculinary-db-password} is {@code SECRET_BEACONCULINARY_DB_PASSWORD}. Locally the values are in
 * devstack/stack.env.
 */
public final class SecretNames {
    /** Lower-case letters and digits, groups joined by single hyphens. Length is checked separately (1 to 127). */
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    public static final int MAX_LENGTH = 127;
    public static final String ENVIRONMENT_PREFIX = "SECRET_";

    private SecretNames() {
    }

    public static boolean isValid(String name) {
        return name != null && name.length() <= MAX_LENGTH && PATTERN.matcher(name).matches();
    }

    /** The name, checked; IllegalArgumentException naming the rule (the name isn't a secret, so it may be shown). */
    public static String require(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("Not a valid secret name: '" + name
                    + "' (lower-case letters and digits joined by single hyphens, at most " + MAX_LENGTH + " characters)");
        }
        return name;
    }

    /** The environment variable that holds this secret locally, e.g. SECRET_BEACONCULINARY_DB_PASSWORD. */
    public static String environmentVariable(String name) {
        return ENVIRONMENT_PREFIX + require(name).toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
