package com.beaconculinary.api.tills;

import java.util.UUID;

/**
 * O2: the till behind a request. A registered till has a device ID, code and name; every other
 * browser is the shared unregistered till ({@link #UNREGISTERED}), whose device ID is null.
 */
public record Till(UUID deviceId, String code, String name) {
    /** How the unregistered till is named in responses and reports. */
    public static final String UNREGISTERED_NAME = "Unregistered till";
    public static final Till UNREGISTERED = new Till(null, null, UNREGISTERED_NAME);

    public boolean registered() {
        return deviceId != null;
    }

    /** "T1 (Till 1 (front))", or "the unregistered till", for messages. */
    public String label() {
        return registered() ? code + " (" + name + ")" : "the unregistered till";
    }

    /** The name to show for a stored till: the unregistered till's name when there is no device. */
    public static String displayName(UUID deviceId, String tillName) {
        return deviceId == null ? UNREGISTERED_NAME : tillName;
    }
}
