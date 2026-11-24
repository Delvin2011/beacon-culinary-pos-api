package com.beaconculinary.api.support.tenancy;

import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * T1-2, TESTS ONLY: makes the customer named in the {@value #HEADER} header (a slug) current for the request, so
 * multi-customer mode can be tested before T1-3 resolves the customer from the address. It lives in src/test only, so
 * it is never in the application jar (TestCustomerMechanismGuardTest checks that), and only multi-mode tests that
 * import {@link TestCustomerConfig} register it. An unknown slug sets nothing: the request then fails with no customer.
 */
public class TestCustomerFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Test-Customer";

    private final CustomerDirectory directory;

    public TestCustomerFilter(CustomerDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var slug = request.getHeader(HEADER);
        var customer = slug == null ? null : directory.findBySlug(slug).map(CurrentCustomer::of).orElse(null);
        if (customer == null) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = CustomerContext.open(customer)) {
            chain.doFilter(request, response);
        }
    }
}
