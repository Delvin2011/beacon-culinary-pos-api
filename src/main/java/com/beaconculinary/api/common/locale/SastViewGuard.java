package com.beaconculinary.api.common.locale;

import java.time.ZoneId;
import java.util.Set;

/**
 * T0-2: the SQL report views (V85, V87, V91, V92, V111) convert stored UTC times with
 * {@code AT TIME ZONE 'South Africa Standard Time'}, so their trading day and sale hour are SAST
 * whatever {@code site.time-zone} says. Until the views take the zone from the site, the app
 * refuses to start with a zone the views don't agree with. See docs/2026-11-19-site-locale.md.
 */
public final class SastViewGuard {
    /**
     * The IANA zones that map to Windows "South Africa Standard Time" (Unicode CLDR
     * windowsZones): UTC+2 all year, no daylight saving.
     */
    public static final Set<String> SAST_ZONES = Set.of(
            "Africa/Johannesburg", "Africa/Maseru", "Africa/Mbabane", "Africa/Gaborone", "Africa/Harare",
            "Africa/Lusaka", "Africa/Maputo", "Africa/Blantyre", "Africa/Kigali", "Africa/Bujumbura",
            "Africa/Lubumbashi", "Etc/GMT-2");

    private SastViewGuard() {
    }

    /** Throws {@link IllegalStateException} when the zone is not one the SQL views agree with. */
    public static void check(ZoneId zone) {
        if (!SAST_ZONES.contains(zone.getId())) {
            throw new IllegalStateException("site.time-zone '" + zone.getId() + "' is not supported yet: the SQL report "
                    + "views (V85, V87, V91, V92, V111) assume South Africa Standard Time (UTC+2) for the trading day "
                    + "and sale hour, so reports would disagree with the app. Use a zone that maps to South Africa "
                    + "Standard Time, such as Africa/Johannesburg. See docs/2026-11-19-site-locale.md.");
        }
    }
}
