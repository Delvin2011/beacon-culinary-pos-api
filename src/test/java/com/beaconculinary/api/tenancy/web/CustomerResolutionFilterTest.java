package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.common.logging.RequestContextFilter;
import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.tenancy.routing.CustomerNotServedException;
import com.beaconculinary.api.tenancy.routing.UnknownCustomerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1-3: the customer filter on its own. The host (Host, or X-Forwarded-Host from a trusted proxy only; case, port and
 * trailing dot ignored; malformed refused), the backend's own addresses, the contract answers per status (no customer
 * named in any body), the customer set for the request and cleared after, and T1-2's exceptions mapped on the way out.
 */
class CustomerResolutionFilterTest {
    private static final String TRUSTED_PROXY = "172.30.0.5";
    private final Map<String, Customer> customers = new java.util.HashMap<>();
    private CustomerResolutionFilter filter;

    @BeforeEach
    void setUp() {
        customers.clear();
        add(1, "alpha", CustomerStatus.ACTIVE);
        add(2, "suspendedco", CustomerStatus.SUSPENDED);
        add(3, "newco", CustomerStatus.PROVISIONING);
        add(4, "brokenco", CustomerStatus.MIGRATION_FAILED);
        add(5, "oldco", CustomerStatus.ARCHIVED);
        var directory = mock(CustomerDirectory.class);
        when(directory.findByHost(anyString())).thenAnswer(call -> Optional.ofNullable(customers.get((String) call.getArgument(0))));
        filter = new CustomerResolutionFilter(directory, TRUSTED_PROXY::equals, List.of("localhost", "Backend"), new ObjectMapper());
    }

    @Test
    void theHostHeader_picksTheCustomer_forTheWholeRequest_andItIsClearedAfter() throws Exception {
        var seen = new AtomicReference<String>();
        var request = request("alpha.pos.localhost", "/orders");
        var response = run(request, (req, res) -> seen.set(CustomerContext.require().slug()));
        assertThat(seen.get()).isEqualTo("alpha");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(RequestContextFilter.TENANT_ATTRIBUTE)).isEqualTo("alpha");
        assertThat(CustomerContext.current()).as("cleared after the request").isEmpty();
    }

    @Test
    void caseAPortAndATrailingDot_areIgnored() throws Exception {
        for (var host : List.of("Alpha.POS.localhost", "alpha.pos.localhost:8443", "alpha.pos.localhost.")) {
            var seen = new AtomicReference<String>();
            run(request(host, "/orders"), (req, res) -> seen.set(CustomerContext.require().slug()));
            assertThat(seen.get()).as(host).isEqualTo("alpha");
        }
    }

    @Test
    void xForwardedHost_isBelievedFromATrustedProxyOnly() throws Exception {
        var fromProxy = request("backend:8080", "/orders");
        fromProxy.setRemoteAddr(TRUSTED_PROXY);
        fromProxy.addHeader("X-Forwarded-Host", "alpha.pos.localhost");
        var seen = new AtomicReference<String>();
        run(fromProxy, (req, res) -> seen.set(CustomerContext.require().slug()));
        assertThat(seen.get()).isEqualTo("alpha");

        // A client can't pick a customer with the header: its Host (the backend's own address) is used.
        var fromClient = request("localhost", "/orders");
        fromClient.setRemoteAddr("10.1.2.3");
        fromClient.addHeader("X-Forwarded-Host", "alpha.pos.localhost");
        var response = run(fromClient, (req, res) -> { throw new AssertionError("must not reach the application"); });
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void aMalformedOrMissingHost_is400WithNoDetail() throws Exception {
        for (var host : new String[]{"bad host!", "[::1]:443", "a..b", null}) {
            var response = run(request(host, "/orders"), (req, res) -> { throw new AssertionError("not reached"); });
            assertThat(response.getStatus()).as(String.valueOf(host)).isEqualTo(400);
            assertThat(response.getContentAsString()).isEmpty();
        }
    }

    @Test
    void theBackendsOwnAddresses_answerHealthOnly() throws Exception {
        var reached = new ArrayList<String>();
        var health = run(request("localhost:8080", "/health"), (req, res) -> reached.add("health"));
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(reached).containsExactly("health");
        assertThat(CustomerContext.current()).isEmpty();

        for (var host : List.of("localhost", "backend:8080")) {
            var other = run(request(host, "/public/board/today"), (req, res) -> reached.add("other"));
            assertThat(other.getStatus()).as(host).isEqualTo(404);
            assertThat(other.getContentAsString()).isEmpty();
        }
        assertThat(reached).containsExactly("health");
    }

    @Test
    void theContract_perStatus_onEveryPath_namingNoCustomer() throws Exception {
        for (var path : List.of("/orders", "/health", "/public/board/today")) {
            expectEmpty("nobody.pos.localhost", path, 404);
            expectEmpty("oldco.pos.localhost", path, 404);
            expectError("suspendedco.pos.localhost", path, "CUSTOMER_SUSPENDED");
            expectError("newco.pos.localhost", path, "CUSTOMER_UNAVAILABLE");
            expectError("brokenco.pos.localhost", path, "CUSTOMER_UNAVAILABLE");
        }
    }

    @Test
    void t12Exceptions_onTheWayOut_areAnsweredAsTheContractSays() throws Exception {
        var suspended = run(request("alpha.pos.localhost", "/orders"), (req, res) -> {
            throw new CannotCreateTransactionException("no connection", new CustomerNotServedException("alpha", CustomerStatus.SUSPENDED));
        });
        assertThat(suspended.getStatus()).isEqualTo(503);
        assertThat(suspended.getContentAsString()).contains("\"code\":\"CUSTOMER_SUSPENDED\"").doesNotContain("alpha");

        var unknown = run(request("alpha.pos.localhost", "/orders"), (req, res) -> {
            throw new IllegalStateException(new UnknownCustomerException("alpha"));
        });
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(unknown.getContentAsString()).isEmpty();

        assertThatThrownBy(() -> run(request("alpha.pos.localhost", "/orders"), (req, res) -> {
            throw new IllegalStateException(new NoCurrentCustomerException());
        })).as("a bug: left to the usual 500").hasRootCauseInstanceOf(NoCurrentCustomerException.class);
        assertThat(CustomerContext.current()).isEmpty();
    }

    @Test
    void asyncDispatches_areFilteredToo() {
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
    }

    // --- helpers ---

    private void expectEmpty(String host, String path, int status) throws Exception {
        var response = run(request(host, path), (req, res) -> { throw new AssertionError("not reached: " + host); });
        assertThat(response.getStatus()).as(host + path).isEqualTo(status);
        assertThat(response.getContentAsString()).as(host + path).isEmpty();
    }

    private void expectError(String host, String path, String code) throws Exception {
        var response = run(request(host, path), (req, res) -> { throw new AssertionError("not reached: " + host); });
        assertThat(response.getStatus()).as(host + path).isEqualTo(503);
        var body = response.getContentAsString();
        assertThat(body).as(host + path).contains("\"code\":\"" + code + "\"").contains("\"error\":");
        var slug = host.substring(0, host.indexOf('.'));
        assertThat(body).as("no customer named").doesNotContain(slug);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String host, String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("10.9.8.7");
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    private void add(long id, String slug, CustomerStatus status) {
        customers.put(slug + ".pos.localhost", new Customer(id, slug, "Display " + slug, status, List.of(slug + ".pos.localhost"), null, null));
    }
}
