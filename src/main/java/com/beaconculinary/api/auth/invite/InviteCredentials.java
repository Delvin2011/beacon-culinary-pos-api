package com.beaconculinary.api.auth.invite;

/** T0-6a: what accepting an invite sets. */
public enum InviteCredentials {
    PASSWORD, PIN, BOTH;

    public boolean includesPassword() {
        return this != PIN;
    }

    public boolean includesPin() {
        return this != PASSWORD;
    }

    public static InviteCredentials of(boolean password, boolean pin) {
        if (password && pin) {
            return BOTH;
        }
        if (password) {
            return PASSWORD;
        }
        if (pin) {
            return PIN;
        }
        throw new IllegalArgumentException("an invite sets a password, a PIN or both");
    }
}
