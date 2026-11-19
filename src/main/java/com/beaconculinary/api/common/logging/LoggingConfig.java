package com.beaconculinary.api.common.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * T0-1: registers {@link RequestContextFilter} ahead of Spring Security (order -100). The bean is not
 * named requestContextFilter: Spring MVC already has a bean of that name.
 */
@Configuration
public class LoggingConfig {
    static final int REQUEST_CONTEXT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public FilterRegistrationBean<RequestContextFilter> requestLogContextFilter(
            @Value("${logging.request.skip-paths:/health,/**/stream}") List<String> skipPaths) {
        var registration = new FilterRegistrationBean<>(new RequestContextFilter(skipPaths));
        registration.setOrder(REQUEST_CONTEXT_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
