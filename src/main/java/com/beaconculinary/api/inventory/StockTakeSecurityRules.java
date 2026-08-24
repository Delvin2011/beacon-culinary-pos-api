package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** Stage 5.2.5 — submitting a Stock Take (create/list/read) is open to STOCK_CLERK and up;
 * reviewing one is STOCK_ADMIN and up. The review matcher must be registered before the general
 * one — Spring Security's authorizeHttpRequests takes the first match. Same shape as
 * StockRequestSecurityRules. */
@Component
public class StockTakeSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry
            .requestMatchers(HttpMethod.POST, "/stock-takes/*/review")
                .hasAnyRole(Role.STOCK_ADMIN.name(), Role.ADMIN.name())
            .requestMatchers("/stock-takes", "/stock-takes/**")
                .hasAnyRole(Role.STOCK_CLERK.name(), Role.STOCK_ADMIN.name(), Role.ADMIN.name());
    }
}
