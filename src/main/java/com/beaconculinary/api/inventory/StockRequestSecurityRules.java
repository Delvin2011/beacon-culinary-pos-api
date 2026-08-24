package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** Stage 5 Revision — authoring a Stock Request (create/list/read) is open to STOCK_CLERK and
 * up; authorizing one (action) is STOCK_ADMIN and up. The action matcher must be registered
 * before the general one — Spring Security's authorizeHttpRequests takes the first match. */
@Component
public class StockRequestSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry
            .requestMatchers(HttpMethod.POST, "/stock-requests/*/action")
                .hasAnyRole(Role.STOCK_ADMIN.name(), Role.ADMIN.name())
            .requestMatchers("/stock-requests", "/stock-requests/**")
                .hasAnyRole(Role.STOCK_CLERK.name(), Role.STOCK_ADMIN.name(), Role.ADMIN.name());
    }
}
