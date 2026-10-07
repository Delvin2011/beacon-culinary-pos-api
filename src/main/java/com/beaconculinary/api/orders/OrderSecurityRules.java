package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class OrderSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        // POS Oversight B1: an ADMIN can now ring orders up at the till, so it can also report a
        // print failure on them — the rule collapses into the general orders rule.
        registry
            .requestMatchers("/orders/**").hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name());
    }
}
