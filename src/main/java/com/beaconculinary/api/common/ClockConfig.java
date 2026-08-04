package com.beaconculinary.api.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
