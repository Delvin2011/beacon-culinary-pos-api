package com.beaconculinary.api.common;

import com.beaconculinary.api.common.locale.SiteLocale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    /** The system clock in the site's business zone (site.time-zone). Code that needs the zone
     * takes it from {@link SiteLocale}, not from {@code clock.getZone()}. */
    @Bean
    public Clock clock(SiteLocale siteLocale) {
        return Clock.system(siteLocale.zone());
    }
}
