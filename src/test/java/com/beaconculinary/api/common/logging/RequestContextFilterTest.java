package com.beaconculinary.api.common.logging;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T0-1: RequestContextFilter on its own: the MDC inside the chain, failures and skip patterns. */
@ExtendWith(OutputCaptureExtension.class)
class RequestContextFilterTest {
    private static final String PROXY = "172.30.0.10";
    private final RequestContextFilter filter =
            new RequestContextFilter(List.of("/health", "/**/stream"), PROXY::equals);

    @Test
    void insideTheChain_theMdcHoldsTheRequestFields_andTheDefaultTenant() throws Exception {
        var request = new MockHttpServletRequest("POST", "/orders");
        request.setQueryString("secret=1");
        var seen = new AtomicReference<java.util.Map<String, String>>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.getCopyOfContextMap()));

        assertThat(seen.get())
                .containsEntry("method", "POST")
                .containsEntry("path", "/orders")
                .containsEntry("tenantId", "default")
                .containsKey("requestId");
        assertThat(seen.get().values()).noneMatch(value -> value.contains("secret"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anExceptionFromTheChain_isLoggedAs500_rethrown_andTheMdcCleared(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/orders/today");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(output.getAll()).contains("request GET /orders/today status=500");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void streamsAndHealth_areNotLogged(CapturedOutput output) throws Exception {
        for (var path : List.of("/health", "/kitchen/stream", "/public/board/stream", "/bar/stream")) {
            filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), (req, res) -> { });
        }
        assertThat(output.getAll()).doesNotContain("request GET");
    }

    @Test
    void theRequestLine_namesTheForwardedHost_whenTheProxyIsTrusted(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/orders/today");
        request.setRemoteAddr(PROXY);
        request.addHeader("Host", "backend:8080");
        request.addHeader("X-Forwarded-Host", "BeaconCulinary.pos.localhost");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(output.getAll()).contains("request GET /orders/today status=200")
                .contains("host=beaconculinary.pos.localhost");
    }

    @Test
    void host_ignoresXForwardedHostFromAnUntrustedPeer_andRefusesUnsafeValues() {
        var direct = new MockHttpServletRequest("GET", "/x");
        direct.setRemoteAddr("203.0.113.9");
        direct.addHeader("Host", "api.example.test:8443");
        direct.addHeader("X-Forwarded-Host", "forged.example.test");
        assertThat(filter.host(direct)).isEqualTo("api.example.test:8443");

        var chained = new MockHttpServletRequest("GET", "/x");
        chained.setRemoteAddr(PROXY);
        chained.addHeader("X-Forwarded-Host", "testcustomer.pos.localhost, backend");
        assertThat(filter.host(chained)).isEqualTo("testcustomer.pos.localhost");

        for (var unsafe : List.of("evil host", "a\nb", "x".repeat(254), "h:123456", "<script>")) {
            var request = new MockHttpServletRequest("GET", "/x");
            request.setRemoteAddr(PROXY);
            request.addHeader("X-Forwarded-Host", unsafe);
            assertThat(filter.host(request)).as(unsafe).isEqualTo("invalid");
        }

        var noHost = new MockHttpServletRequest("GET", "/x");
        noHost.removeHeader("Host");
        assertThat(filter.host(noHost)).isIn("-", "localhost");
    }

    @Test
    void requestIdPattern() {
        assertThat(RequestContextFilter.requestId("abcdefgh")).isEqualTo("abcdefgh");
        assertThat(RequestContextFilter.requestId("A-b_c.0123456789")).isEqualTo("A-b_c.0123456789");
        assertThat(RequestContextFilter.requestId("x".repeat(64))).isEqualTo("x".repeat(64));
        assertThat(RequestContextFilter.requestId("x".repeat(65))).hasSize(36);
        assertThat(RequestContextFilter.requestId("abc")).hasSize(36);
        assertThat(RequestContextFilter.requestId(null)).hasSize(36);
        assertThat(RequestContextFilter.requestId("abcdefgh\n")).hasSize(36);
    }
}
