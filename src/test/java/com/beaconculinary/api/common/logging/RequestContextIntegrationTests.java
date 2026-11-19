package com.beaconculinary.api.common.logging;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T0-1: the request id, the request log line and the MDC lifecycle, through the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestContextIntegrationTests {
    private static final String HEADER = RequestContextFilter.HEADER;

    @Autowired
    private MockMvc mockMvc;

    private static String requestLine(CapturedOutput output, String requestId) {
        return Arrays.stream(output.getAll().split("\\R"))
                .filter(line -> line.contains("request ") && line.contains(requestId))
                .findFirst().orElse(null);
    }

    @Test
    void withoutAnInboundId_aNewOneIsGenerated_echoed_andOnTheRequestLogLine(CapturedOutput output) throws Exception {
        var requestId = mockMvc.perform(get("/menu/today"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(UUID.fromString(requestId)).isNotNull();
        var line = requestLine(output, requestId);
        assertThat(line).contains("request GET /menu/today status=200 durationMs=");
    }

    @Test
    void aSafeInboundId_isKept(CapturedOutput output) throws Exception {
        var inbound = "till-T1.req_0001";
        mockMvc.perform(get("/menu/today").header(HEADER, inbound))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).isEqualTo(inbound));

        assertThat(requestLine(output, inbound)).isNotNull();
    }

    @Test
    void anUnsafeInboundId_isReplaced(CapturedOutput output) throws Exception {
        for (var unsafe : new String[]{"short", "has a space here", "x".repeat(65), "abcdefgh\r\nForged: yes",
                "abcdefgh{json}", "abcdefgh%0d%0a"}) {
            var echoed = mockMvc.perform(get("/menu/today").header(HEADER, unsafe))
                    .andReturn().getResponse().getHeader(HEADER);
            assertThat(echoed).as(unsafe).isNotEqualTo(unsafe);
            assertThat(UUID.fromString(echoed)).isNotNull();
        }
        assertThat(output.getAll()).doesNotContain("Forged: yes");
    }

    @Test
    void theRouteIsTheMatchedPattern_andTheQueryStringIsNeverLogged(CapturedOutput output) throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);
        var requestId = mockMvc.perform(get("/admin/sale-exceptions/987654321")
                        .queryParam("note", "query-value-must-not-appear")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(requestLine(output, requestId))
                .contains("request GET /admin/sale-exceptions/{id} status=404");
        assertThat(output.getAll()).doesNotContain("query-value-must-not-appear");
    }

    @Test
    void aRequestRefusedBySpringSecurity_isLoggedWithItsStatus(CapturedOutput output) throws Exception {
        var requestId = mockMvc.perform(get("/admin/staff"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(requestId).isNotNull();
        assertThat(requestLine(output, requestId)).contains("request GET /admin/staff status=401");
    }

    @Test
    void healthPollsAndPreflights_getAnIdButNoRequestLine(CapturedOutput output) throws Exception {
        var healthId = mockMvc.perform(get("/health"))
                .andReturn().getResponse().getHeader(HEADER);
        var preflightId = mockMvc.perform(options("/orders")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(healthId).isNotNull();
        assertThat(preflightId).isNotNull();
        assertThat(requestLine(output, healthId)).isNull();
        assertThat(requestLine(output, preflightId)).isNull();
    }

    @Test
    void theMdcIsEmptyAfterEachRequest_onTheSameThread() throws Exception {
        // MockMvc runs the filter chain on the test thread, so this is the reused-thread case.
        MDC.put("leftOver", "from-an-earlier-task");
        mockMvc.perform(get("/menu/today")).andExpect(status().isOk());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        mockMvc.perform(get("/admin/staff")).andExpect(status().isUnauthorized());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
