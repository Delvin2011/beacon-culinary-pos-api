package com.beaconculinary.api.settings;

/** T0-4a: 404 UNKNOWN_SETTING, a reset of a key that isn't a site setting. */
public class UnknownSettingException extends RuntimeException {
    public UnknownSettingException(String key) {
        super("There is no setting " + key + ".");
    }
}
