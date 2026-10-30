package com.beaconculinary.api.bar;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class BarSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        // BAR_DISPLAY until U2: the Barista, and ADMIN. Kitchen staff can't use the bar display.
        registry.requestMatchers("/bar/**").hasAnyRole(Role.BARISTA.name(), Role.ADMIN.name());
    }
}
