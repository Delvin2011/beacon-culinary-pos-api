package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class KitchenSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers("/kitchen/**").hasAnyRole(Role.KITCHEN.name(), Role.ADMIN.name());
    }
}
