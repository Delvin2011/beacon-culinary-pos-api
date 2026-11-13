package com.beaconculinary.api.auth.protection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LoginProtectionProperties.class)
public class LoginProtectionConfig {
}
