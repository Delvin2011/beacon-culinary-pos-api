package com.beaconculinary.api.common;

import com.beaconculinary.api.common.locale.SiteLocale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {
    /** @deprecated T0-2: use {@link SiteLocale#zone()}; removed once every caller has moved (next commit). */
    @Deprecated(forRemoval = true)
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");

    /** The system clock in the site's business zone (site.time-zone). */
    @Bean
    public Clock clock(SiteLocale siteLocale) {
        return Clock.system(siteLocale.zone());
    }
}
