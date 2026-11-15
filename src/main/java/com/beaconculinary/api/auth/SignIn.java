package com.beaconculinary.api.auth;

import java.time.Clock;

/**
 * How and when a session started (WS1-1b), carried in access and refresh tokens and kept on
 * refresh: {@code amr} ("pwd" or "pin"), {@code auth_time} (seconds since the epoch, UTC, of the
 * original sign-in), and {@code dev} (the POS till's device ID, on PIN sign-ins from a registered
 * till). Any field may be null on tokens issued before this release.
 */
public record SignIn(String amr, Long authTime, String deviceId) {
    public static final String AMR_CLAIM = "amr";
    public static final String AUTH_TIME_CLAIM = "auth_time";
    public static final String DEVICE_CLAIM = "dev";
    public static final String PASSWORD = "pwd";
    public static final String PIN = "pin";
    public static final SignIn NONE = new SignIn(null, null, null);

    public static SignIn password(Clock clock) {
        return new SignIn(PASSWORD, clock.instant().getEpochSecond(), null);
    }

    public static SignIn pin(Clock clock, String deviceId) {
        return new SignIn(PIN, clock.instant().getEpochSecond(), deviceId);
    }
}
