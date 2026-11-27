package com.beaconculinary.api.receipts;

import com.beaconculinary.api.common.SecurityRules;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** T1-7: GET /public/branding needs no sign-in. */
@Component
public class PublicBrandingSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers("/public/branding").permitAll();
    }
}
