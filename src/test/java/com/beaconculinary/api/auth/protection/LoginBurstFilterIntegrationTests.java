package com.beaconculinary.api.auth.protection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.MAPPER;
import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.RANDOM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WS1-1 burst limit in enforce mode, through the real security filter chain: a small bucket
 * (3 requests, refilling 1 per hour) so the 4th request from one address is refused with the 429
 * contract, CORS headers included, and without a login_attempts row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.login-protection.mode=enforce",
        "security.login-protection.burst.capacity=3",
        "security.login-protection.burst.refill-tokens=1",
        "security.login-protection.burst.refill-period=1h"
})
class LoginBurstFilterIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private MvcResult pinLogin(String ip, long cashierId) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:3000")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content("{\"cashierId\":" + cashierId + ",\"pin\":\"000000\"}")).andReturn();
    }

    private int attemptsFor(long cashierId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts] WHERE [identifier] = ?", Integer.class,
                String.valueOf(cashierId));
    }

    @Test
    void fourthRequestFromOneAddress_getsTheRateLimited429_withRetryAfterAndCors_andIsNotRecorded() throws Exception {
        var ip = "198.51.100." + (1 + RANDOM.nextInt(200));
        var cashierId = 900_000_000L + RANDOM.nextInt(1_000_000);
        for (int i = 0; i < 3; i++) {
            assertThat(pinLogin(ip, cashierId).getResponse().getStatus()).isEqualTo(401);
        }
        var refused = pinLogin(ip, cashierId);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        var body = MAPPER.readTree(refused.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).isEqualTo("Too many attempts. Try again later.");
        var retryAfter = body.get("retryAfterSeconds").asLong();
        assertThat(retryAfter).isBetween(1L, 3600L);
        assertThat(refused.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(retryAfter));
        assertThat(refused.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(refused.getResponse().getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        assertThat(attemptsFor(cashierId)).as("burst refusals write no login_attempts row").isEqualTo(3);

        // Another address has its own bucket.
        assertThat(pinLogin("198.51.100.250", cashierId).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void otherEndpoints_areNotLimited() throws Exception {
        for (int i = 0; i < 6; i++) {
            var result = mockMvc.perform(get("/menu/today").with(request -> {
                request.setRemoteAddr("198.51.100.251");
                return request;
            })).andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
        }
    }
}
