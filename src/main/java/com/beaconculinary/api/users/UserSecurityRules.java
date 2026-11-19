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
            .requestMatchers(HttpMethod.POST, "/users/*/change-password").authenticated();
        // Nothing else lives under /users (T0-3). Any other path falls to the global authenticated()
        // rule, so a signed-in caller gets 404 and an anonymous one 401.
    }
}
