package com.beaconculinary.api.common.locale;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T0-2: the {@link SiteLocale} bean. Startup fails when a site.* setting is invalid or the zone
 * is not one the SQL report views agree with ({@link SastViewGuard}). */
@Configuration
@EnableConfigurationProperties(SiteLocaleProperties.class)
public class SiteLocaleConfig {
    @Bean
    public SiteLocale siteLocale(SiteLocaleProperties properties) {
        var locale = SiteLocale.from(properties);
        SastViewGuard.check(locale.zone());
        return locale;
    }
}
