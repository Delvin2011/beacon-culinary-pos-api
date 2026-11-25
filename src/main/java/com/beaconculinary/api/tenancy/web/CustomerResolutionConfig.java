package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.auth.protection.ClientIpResolver;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * T1-3, multi mode only: registers {@link CustomerResolutionFilter} right after the request log filter
 * (HIGHEST_PRECEDENCE + 10), so before Spring Security and every filter that reads a database; and warns at start-up
 * that tokens don't carry their customer yet (until T1-5).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
public class CustomerResolutionConfig {
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 11;
    private static final Logger log = LoggerFactory.getLogger(CustomerResolutionConfig.class);

    @Bean
    public FilterRegistrationBean<CustomerResolutionFilter> customerResolutionFilter(
            CustomerDirectory directory, ClientIpResolver clientIpResolver, ObjectMapper objectMapper,
            @Value("${app.tenancy.platform-hosts:localhost,127.0.0.1}") List<String> platformHosts) {
        var registration = new FilterRegistrationBean<>(
                new CustomerResolutionFilter(directory, clientIpResolver::isTrustedProxy, platformHosts, objectMapper));
        registration.setOrder(ORDER);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }

    /** Until T1-5 binds tokens to their customer, a token from customer A carries a user id B may also have. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnTokensNotCustomerBound() {
        log.warn("multi mode without customer-bound tokens: local development only");
    }
}
