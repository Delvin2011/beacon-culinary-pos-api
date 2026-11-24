package com.beaconculinary.api.tenancy.secrets;

/** T1-1: a secret that has no value. The message names the secret (and where it was looked for), never a value. */
public class MissingSecretException extends RuntimeException {
    private final String secretName;

    public MissingSecretException(String secretName, String whereLooked) {
        super("Secret '" + secretName + "' is not set (" + whereLooked + ")");
        this.secretName = secretName;
    }

    public String getSecretName() {
        return secretName;
    }
}
