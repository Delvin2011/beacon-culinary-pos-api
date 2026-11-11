package com.beaconculinary.api.auth.protection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginBurstFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong nanos = new AtomicLong(0);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(LoginBurstFilter.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private LoginBurstFilter filter(LoginProtectionMode mode, int capacity, int maxClients) {
        var properties = new LoginProtectionProperties();
        properties.setMode(mode);
        properties.getBurst().setCapacity(capacity);
        properties.getBurst().setRefillTokens(1);
        properties.getBurst().setRefillPeriod(Duration.ofHours(1));
        properties.getBurst().setMaxTrackedClients(maxClients);
        return new LoginBurstFilter(properties, new ClientIpResolver(List.of()), objectMapper, nanos::get);
    }

    private MockHttpServletResponse send(LoginBurstFilter filter, String path, String ip) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private long warnings() {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains("burst limit")).count();
    }

    @Test
    void enforce_refusesOnceTheBucketIsEmpty() throws Exception {
        var filter = filter(LoginProtectionMode.ENFORCE, 2, 100);
        assertThat(send(filter, "/auth/login", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(send(filter, "/auth/login", "203.0.113.1").getStatus()).isEqualTo(200);
        var refused = send(filter, "/auth/login", "203.0.113.1");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotNull();
        var body = objectMapper.readTree(refused.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("retryAfterSeconds").asLong()).isEqualTo(Long.parseLong(refused.getHeader("Retry-After")));
    }

    @Test
    void appliesToManagerPinEndpoints_butNotToOtherPathsOrPreflight() throws Exception {
        var filter = filter(LoginProtectionMode.ENFORCE, 1, 100);
        send(filter, "/admin/authorize-session", "203.0.113.2");
        assertThat(send(filter, "/admin/authorize", "203.0.113.2").getStatus()).isEqualTo(429);
        assertThat(send(filter, "/admin/staff", "203.0.113.2").getStatus()).isEqualTo(200);
        assertThat(send(filter, "/menu/today", "203.0.113.2").getStatus()).isEqualTo(200);

        var preflight = new MockHttpServletRequest("OPTIONS", "/auth/login");
        preflight.setRemoteAddr("203.0.113.2");
        var response = new MockHttpServletResponse();
        filter.doFilter(preflight, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void monitor_onlyLogs_andOff_doesNothing() throws Exception {
        var monitor = filter(LoginProtectionMode.MONITOR, 1, 100);
        send(monitor, "/auth/login", "203.0.113.3");
        assertThat(send(monitor, "/auth/login", "203.0.113.3").getStatus()).isEqualTo(200);
        assertThat(warnings()).isEqualTo(1);

        var off = filter(LoginProtectionMode.OFF, 1, 100);
        for (int i = 0; i < 5; i++) {
            assertThat(send(off, "/auth/login", "203.0.113.4").getStatus()).isEqualTo(200);
        }
        assertThat(off.trackedClients()).isZero();
    }

    @Test
    void atMostOneWarningPerAddressPerInterval() throws Exception {
        var filter = filter(LoginProtectionMode.ENFORCE, 1, 100);
        send(filter, "/auth/login", "203.0.113.5");
        for (int i = 0; i < 10; i++) {
            send(filter, "/auth/login", "203.0.113.5");
        }
        assertThat(warnings()).isEqualTo(1);

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        send(filter, "/auth/login", "203.0.113.5");
        assertThat(warnings()).isEqualTo(2);
    }

    @Test
    void trackedAddresses_areBounded() throws Exception {
        var filter = filter(LoginProtectionMode.ENFORCE, 5, 3);
        for (int i = 1; i <= 50; i++) {
            send(filter, "/auth/login", "10.9.0." + i);
        }
        assertThat(filter.trackedClients()).isLessThanOrEqualTo(3);
    }
}
