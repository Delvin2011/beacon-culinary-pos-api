package com.beaconculinary.api.tenancy.secrets;

/**
 * T1-1: turns a secret's NAME (as the platform database stores it, e.g. "beaconculinary-db-password") into its value.
 * The platform database never holds a value. Today's implementation reads the environment
 * ({@link EnvironmentSecretResolver}); T1-0b adds Azure Key Vault behind this same interface, with the same names.
 *
 * <p>Callers use the value at once and keep no reference to it; nothing here logs or caches a value.
 */
public interface SecretResolver {

    /**
     * The secret's value.
     *
     * @throws MissingSecretException naming the secret (never a value) when there is none
     * @throws IllegalArgumentException when the name doesn't follow {@link SecretNames#PATTERN}
     */
    String resolve(String secretName);
}
