package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.SecurityRules;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** The /admin/meal-catalog, /admin/component-catalog, /admin/daily-* write endpoints are covered by the global AdminSecurityRules (/admin/**). */
@Component
public class MenuSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry
            .requestMatchers(HttpMethod.GET, "/meal-periods").permitAll()
            .requestMatchers(HttpMethod.GET, "/menu/today").permitAll();
    }
}
