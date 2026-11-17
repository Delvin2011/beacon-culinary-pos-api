package com.beaconculinary.api.offline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OfflineProperties.class)
public class OfflineConfig {
}
