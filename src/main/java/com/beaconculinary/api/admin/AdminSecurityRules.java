package com.beaconculinary.api.admin;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class AdminSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        // A cashier at the till submits whichever admin's PIN is presented — Stage 2.6's
        // manager-override flow, and Stage 3.3's management-session variant — so these two
        // /admin/** paths are deliberately not admin-only.
        registry
            .requestMatchers(HttpMethod.POST, "/admin/authorize", "/admin/authorize-session")
                .hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name())
            .requestMatchers("/admin/**").hasRole(Role.ADMIN.name());
    }
}
