package com.beaconculinary.api.common;

import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O1: GET /health is public and outside device trust and login protection. Run with device
 * trust required and login protection on (the same settings as DeviceGatingIntegrationTests, so
 * the test context is shared). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.device-trust.mode=required",
        "security.login-protection.mode=monitor"
})
@ExtendWith(OutputCaptureExtension.class)
class HealthIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private long loginAttempts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Long.class);
    }

    @Test
    void withoutASessionOrARegisteredTill_itIs200Up_withTheServerTimeInUtc() throws Exception {
        var response = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        var serverTime = MAPPER.readTree(response).get("serverTimeUtc").asText();
        assertThat(serverTime).endsWith("Z");
        assertThat(Instant.parse(serverTime)).isCloseTo(clock.instant(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void anExpiredOrGarbageBearerToken_andAnUnknownDeviceCookie_doNotMatter() throws Exception {
        mockMvc.perform(get("/health").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/health").cookie(new Cookie("deviceToken", "not-a-device-token")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void pollingIsNotRequestLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/meal-periods")).andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain("Request: /health").contains("Request: /meal-periods");
    }

    @Test
    void pollingRecordsNoSignInAttempts() throws Exception {
        var before = loginAttempts();
        for (var i = 0; i < 20; i++) {
            mockMvc.perform(get("/health")).andExpect(status().isOk());
        }
        assertThat(loginAttempts()).isEqualTo(before);
    }
}
