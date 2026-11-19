package com.beaconculinary.api.support;

import com.beaconculinary.api.common.locale.SiteLocale;

import java.time.ZoneId;

/**
 * T0-2: the site locale the tests are written for. Fixture times are Johannesburg local times and
 * expected texts are rand amounts, so this is spelled out here rather than read from the app's
 * settings: a change to the defaults then fails the tests instead of silently moving with them.
 */
public final class TestLocale {
    public static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    public static final SiteLocale SITE = SiteLocale.of("Africa/Johannesburg", "ZAR", "R");

    private TestLocale() {
    }
}
