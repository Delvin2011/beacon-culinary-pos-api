package com.beaconculinary.api.auth;

import com.beaconculinary.api.auth.protection.ClientIpResolver;
import com.beaconculinary.api.auth.protection.LoginBurstFilter;
import com.beaconculinary.api.auth.protection.LoginProtectionProperties;
import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.common.logging.RequestContextFilter;
import com.beaconculinary.api.devices.DeviceFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@AllArgsConstructor
public class SecurityConfig {
    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final List<SecurityRules> featureSecurityRules;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> allowedOrigins) {
        var cors = new CorsConfiguration();
        // T1-3: patterns, so one setting covers every customer's address (https://*.pos.localhost locally,
        // https://*.pos.optimumscs.com in Azure); an exact address is still a valid pattern.
        cors.setAllowedOriginPatterns(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        // WS1-1: lets the cross-origin frontend read Retry-After on a 429. O1: and the
        // Idempotent-Replay header on a 200 that replayed an earlier sale or adjustment.
        // O3: and ETag, so the till can send it back as If-None-Match on the next snapshot refresh.
        // T0-1: and X-Request-Id, so a support report can quote the request.
        cors.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER, "Idempotent-Replay", HttpHeaders.ETAG,
                RequestContextFilter.HEADER));
        cors.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
            LoginProtectionProperties loginProtection, ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper, DeviceFilters deviceFilters) throws Exception {
        http
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .sessionManagement(c ->
                    c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(c -> {
                    c.requestMatchers("/error").permitAll();
                    featureSecurityRules.forEach(r -> r.configure(c));
                    c.anyRequest().authenticated();
                }
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // WS1-1 burst limit on sign-in and manager-PIN endpoints. Created here, not as a
            // @Component, so it runs only in the security chain.
            .addFilterBefore(new LoginBurstFilter(loginProtection, clientIpResolver, objectMapper),
                    JwtAuthenticationFilter.class)
            // WS1-1b: recognise the POS till from its deviceToken cookie (after the burst limit,
            // before JWT), and log cashier order/shift requests without one (after JWT).
            .addFilterBefore(deviceFilters.resolutionFilter(), JwtAuthenticationFilter.class)
            .addFilterAfter(deviceFilters.unboundRequestFilter(), JwtAuthenticationFilter.class)
            .exceptionHandling(c -> {
                // T1-5: 401 TENANT_MISMATCH for a token from another customer; otherwise the plain 401 as before.
                var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
                c.authenticationEntryPoint((request, response, authException) -> {
                    if (request.getAttribute(TenantBinding.MISMATCH_ATTRIBUTE) != null) {
                        TenantBinding.write(response, objectMapper);
                    } else {
                        unauthorized.commence(request, response, authException);
                    }
                });
                c.accessDeniedHandler(((request, response, accessDeniedException) ->
                    response.setStatus(HttpStatus.FORBIDDEN.value())));
            });

        return http.build();
    }
}
