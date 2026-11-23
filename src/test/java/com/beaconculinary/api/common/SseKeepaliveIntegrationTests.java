package com.beaconculinary.api.common;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * T1-L0 follow-up: the SSE keepalive. A quiet stream receives ":keepalive" comments at the interval (200 ms here;
 * 25 s by default), and nothing a client would read as an event; a stream the client has left gets no more and leaves
 * its channel; keepalives touch no session or device.
 */
@SpringBootTest(properties = "app.sse.keepalive-interval=200ms")
@AutoConfigureMockMvc
class SseKeepaliveIntegrationTests {
    private static final Pattern KEEPALIVE = Pattern.compile(":keepalive\n\n");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseKeepalive keepalive;

    @Autowired
    private JdbcTemplate jdbc;

    private String kitchenToken;
    private String baristaToken;

    @BeforeEach
    void setUp() throws Exception {
        kitchenToken = AuthTestHelper.loginAsKitchen(mockMvc);
        baristaToken = AuthTestHelper.loginAsBarista(mockMvc);
    }

    @Test
    void quietStreams_receiveKeepalivesAtTheInterval_andNoEvents() throws Exception {
        assertThat(keepalive.enabled()).isTrue();
        var streams = openAll();

        Thread.sleep(1_100);

        for (var stream : streams) {
            var body = stream.getResponse().getContentAsString();
            var uri = stream.getRequest().getRequestURI();
            assertThat(body).as(uri).startsWith(":connected\n\n");
            // 1.1 s at 200 ms: about 5; at least 3 allows for a slow scheduler.
            assertThat(KEEPALIVE.matcher(body).results().count()).as(uri + " keepalives").isGreaterThanOrEqualTo(3);
            // Only comment lines and the blank line after each: nothing a client reads as an event.
            assertThat(body).as(uri).doesNotContain("data:").doesNotContain("event:");
            assertThat(body.replace(":connected\n\n", "").replace(":keepalive\n\n", "")).as(uri).isEmpty();
        }
    }

    @Test
    void aStreamTheClientHasLeft_getsNoMoreKeepalives_andLeavesItsChannel() throws Exception {
        var stream = mockMvc.perform(get("/public/board/stream")).andExpect(request().asyncStarted()).andReturn();
        Thread.sleep(500);
        var openBefore = keepalive.openStreams();

        // The client goes away: the container completes the async request, as Tomcat does on a closed connection.
        ((MockAsyncContext) stream.getRequest().getAsyncContext()).complete();
        var lengthAtClose = stream.getResponse().getContentAsString().length();
        Thread.sleep(700);

        assertThat(keepalive.openStreams()).isEqualTo(openBefore - 1);
        assertThat(stream.getResponse().getContentAsString()).hasSize(lengthAtClose);
    }

    @Test
    void keepalives_touchNoSessionAndNoDevice() throws Exception {
        openAll();
        Thread.sleep(300);
        var sessionsBefore = jdbc.queryForObject("SELECT CONVERT(varchar(40), MAX(last_seen_at), 121) FROM user_sessions", String.class);
        var devicesBefore = jdbc.queryForObject("SELECT CONVERT(varchar(40), MAX(last_seen_at), 121) FROM device_credentials", String.class);
        var sent = keepalive.sendKeepalives();

        Thread.sleep(800);

        assertThat(sent).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT CONVERT(varchar(40), MAX(last_seen_at), 121) FROM user_sessions", String.class))
                .isEqualTo(sessionsBefore);
        assertThat(jdbc.queryForObject("SELECT CONVERT(varchar(40), MAX(last_seen_at), 121) FROM device_credentials", String.class))
                .isEqualTo(devicesBefore);
    }

    private List<MvcResult> openAll() throws Exception {
        return List.of(
                mockMvc.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + kitchenToken))
                        .andExpect(request().asyncStarted()).andReturn(),
                mockMvc.perform(get("/bar/orders/stream").header("Authorization", "Bearer " + baristaToken))
                        .andExpect(request().asyncStarted()).andReturn(),
                mockMvc.perform(get("/public/board/stream")).andExpect(request().asyncStarted()).andReturn());
    }
}
