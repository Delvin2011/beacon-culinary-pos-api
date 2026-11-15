package com.beaconculinary.api.devices;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeviceTrustProperties.class)
public class DeviceTrustConfig {
}
