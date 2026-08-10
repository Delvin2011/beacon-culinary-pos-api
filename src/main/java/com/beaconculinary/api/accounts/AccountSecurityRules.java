package com.beaconculinary.api.accounts;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** The /admin/accounts/** endpoints are covered by the global AdminSecurityRules (/admin/**) —
 * only GET /accounts (the till picker) needs a rule here, since it's outside that namespace. */
@Component
public class AccountSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers(HttpMethod.GET, "/accounts").hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name());
    }
}
