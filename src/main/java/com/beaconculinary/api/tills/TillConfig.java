package com.beaconculinary.api.tills;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TillProperties.class)
public class TillConfig {
}
