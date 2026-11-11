package com.beaconculinary.api.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled jobs (first used by the WS1-1 login_attempts retention job). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
