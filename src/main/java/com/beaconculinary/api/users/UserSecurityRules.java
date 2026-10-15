package com.beaconculinary.api.users;

import com.beaconculinary.api.common.SecurityRules;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class UserSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry
            // Pre-login tile lists stay public.
            .requestMatchers(HttpMethod.GET, "/users/cashiers", "/users/login-tiles").permitAll()
            // Anyone signed in may change their OWN password (the service checks it is theirs).
            .requestMatchers(HttpMethod.POST, "/users/*/change-password").authenticated()
            // Everything else under /users — listing, viewing, editing, deleting, and the old public
            // sign-up — is admin-only. Staff are created and managed through /admin/staff.
            .requestMatchers("/users", "/users/**").hasRole(Role.ADMIN.name());
    }
}
