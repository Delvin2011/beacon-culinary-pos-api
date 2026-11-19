package com.beaconculinary.api.tills;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** O2: GET /pos/till-status is public (read before sign-in); the rest of /pos/** is for the POS roles. */
@Component
public class PosSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers(HttpMethod.GET, "/pos/till-status").permitAll();
        // O3: the heartbeat authenticates by the till cookie only (OfflineStatusController); no sign-in.
        registry.requestMatchers(HttpMethod.POST, "/pos/offline/status").permitAll();
        // O4: offline sync authenticates by the till cookie and the signed grants (OfflineSyncService).
        registry.requestMatchers(HttpMethod.POST, "/pos/offline/sales").permitAll();
        registry.requestMatchers("/pos/**").hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name());
    }
}
