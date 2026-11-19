package com.beaconculinary.api.settings;

import java.util.Set;

/**
 * T0-4a: published inside the transaction that saved or reset site settings. Listeners act after
 * it commits: SiteSettings drops its cache, DashboardCache clears (thresholds are baked into
 * cached sections).
 */
public record SiteSettingsChangedEvent(Set<SiteSetting> settings) {
}
