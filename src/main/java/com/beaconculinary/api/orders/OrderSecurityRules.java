package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class OrderSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        // Reporting a print failure is a cashier-at-the-till concern, not an admin one — per spec.
        registry
            .requestMatchers(HttpMethod.POST, "/orders/*/mark-print-failed").hasRole(Role.CASHIER.name())
            .requestMatchers("/orders/**").hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name());
    }
}
