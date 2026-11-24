package com.beaconculinary.api.support.tenancy;

import com.beaconculinary.api.tenancy.CustomerDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * T1-2, TESTS ONLY: registers {@link TestCustomerFilter} right after the request log filter, before Spring Security and
 * the device filters (which read the database). Imported by multi-mode tests only.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestCustomerConfig {

    @Bean
    public FilterRegistrationBean<TestCustomerFilter> testCustomerFilter(CustomerDirectory directory) {
        var registration = new FilterRegistrationBean<>(new TestCustomerFilter(directory));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
