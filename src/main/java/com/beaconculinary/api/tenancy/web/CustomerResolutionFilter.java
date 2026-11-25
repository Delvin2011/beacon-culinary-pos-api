package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.common.logging.RequestContextFilter;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.HostNames;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.tenancy.routing.CustomerNotServedException;
import com.beaconculinary.api.tenancy.routing.UnknownCustomerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * T1-3: in multi-customer mode, every request's customer comes from the address it was sent to, before anything else
 * touches a database (docs/2026-11-25-customer-from-address.md). Registered right after the request log filter, ahead
 * of Spring Security, the burst limit, device resolution and the JWT filter.
 *
 * <p>The host is X-Forwarded-Host from a trusted proxy, otherwise Host (as the request log line has it), normalised as
 * the customer directory stores hosts. The answers (the contract with the frontend, on every path):
 * <ul>
 *   <li>malformed host: 400, empty body;</li>
 *   <li>one of the backend's own addresses (app.tenancy.platform-hosts): /health only (platform health); any other
 *       path 404, empty body;</li>
 *   <li>unknown host, or an ARCHIVED customer: 404, empty body;</li>
 *   <li>SUSPENDED: 503 {@code CUSTOMER_SUSPENDED}; PROVISIONING or MIGRATION_FAILED: 503 {@code CUSTOMER_UNAVAILABLE};</li>
 *   <li>ACTIVE: the customer is current ({@link CustomerContext}) for the whole request, and cleared in finally.</li>
 * </ul>
 * No answer names a customer. On the way out, T1-2's exceptions are mapped the same way (a customer suspended during a
 * request); a database access with no customer is a bug, logged at ERROR and left to the usual 500.
 *
 * <p>Async dispatches (streams) run through it too, so the customer is set and cleared there as well.
 */
public class CustomerResolutionFilter extends OncePerRequestFilter {
    public static final String CUSTOMER_SUSPENDED = "CUSTOMER_SUSPENDED";
    public static final String CUSTOMER_UNAVAILABLE = "CUSTOMER_UNAVAILABLE";
    static final String UNAVAILABLE_MESSAGE = "This service is temporarily unavailable.";

    private static final Logger log = LoggerFactory.getLogger(CustomerResolutionFilter.class);

    private final CustomerDirectory directory;
    private final Predicate<String> trustedProxy;
    private final Set<String> platformHosts;
    private final ObjectMapper objectMapper;

    public CustomerResolutionFilter(CustomerDirectory directory, Predicate<String> trustedProxy,
                                    Collection<String> platformHosts, ObjectMapper objectMapper) {
        this.directory = directory;
        this.trustedProxy = trustedProxy;
        this.platformHosts = platformHosts.stream().map(HostNames::normalise)
                .flatMap(java.util.Optional::stream).collect(Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var host = HostNames.normalise(RequestContextFilter.rawHost(request, trustedProxy)).orElse(null);
        if (host == null) {
            empty(response, HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (platformHosts.contains(host)) {
            if ("/health".equals(request.getRequestURI().substring(request.getContextPath().length()))) {
                chain.doFilter(request, response);
            } else {
                empty(response, HttpServletResponse.SC_NOT_FOUND);
            }
            return;
        }
        var customer = directory.findByHost(host).orElse(null);
        if (customer == null || customer.status() != CustomerStatus.ACTIVE) {
            refuse(response, customer == null ? null : customer.status());
            return;
        }
        request.setAttribute(RequestContextFilter.TENANT_ATTRIBUTE, customer.slug());
        try (var ignored = CustomerContext.open(CurrentCustomer.of(customer))) {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!mapped(e, response)) {
                throw e;
            }
        }
    }

    /** T1-2's exceptions leaving the chain: answered as the contract says; false for anything else. */
    private boolean mapped(Exception e, HttpServletResponse response) throws IOException {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoCurrentCustomerException) {
                log.error("A database access with no current customer (a bug): {}", e.toString(), e);
                return false;
            }
            if (cause instanceof UnknownCustomerException || cause instanceof CustomerNotServedException) {
                if (response.isCommitted()) {
                    return false;
                }
                response.reset();
                refuse(response, cause instanceof CustomerNotServedException notServed ? notServed.status() : null);
                return true;
            }
        }
        return false;
    }

    /** The answer for a customer that isn't served: null (unknown) or its status. */
    private void refuse(HttpServletResponse response, CustomerStatus status) throws IOException {
        if (status == null || status == CustomerStatus.ARCHIVED) {
            empty(response, HttpServletResponse.SC_NOT_FOUND);
        } else if (status == CustomerStatus.SUSPENDED) {
            error(response, CUSTOMER_SUSPENDED);
        } else {
            error(response, CUSTOMER_UNAVAILABLE);
        }
    }

    private static void empty(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(0);
    }

    private void error(HttpServletResponse response, String code) throws IOException {
        var body = objectMapper.writeValueAsBytes(new ErrorDto(UNAVAILABLE_MESSAGE, code));
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
