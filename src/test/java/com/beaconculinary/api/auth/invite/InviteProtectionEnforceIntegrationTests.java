package com.beaconculinary.api.auth.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-6a INVITE_IP in enforce mode: unknown tokens from one client address block that address (429)
 * and alert; another address is unaffected. The threshold is lowered to 3 for the test.
 */
@SpringBootTest(properties = {
        "security.login-protection.mode=enforce",
        "security.login-protection.invite-ip.max-failures=3",
        "security.login-protection.invite-ip.window=15m"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class InviteProtectionEnforceIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GUESSER = "192.0.2.77";
    private static final String OTHER = "192.0.2.78";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearHistory() {
        jdbc.update("DELETE FROM [login_attempts] WHERE [client_ip] IN (?, ?)", GUESSER, OTHER);
    }

    private ResultActions lookupFrom(String ip, String token) throws Exception {
        return mockMvc.perform(post("/auth/invites/lookup").with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("token", token))));
    }

    @Test
    void unknownTokensFromOneAddressBlockThatAddress_andAlert(CapturedOutput output) throws Exception {
        for (int i = 0; i < 3; i++) {
            lookupFrom(GUESSER, InviteTokens.newToken()).andExpect(status().isNotFound());
        }
        lookupFrom(GUESSER, InviteTokens.newToken()).andExpect(status().isTooManyRequests());
        lookupFrom(OTHER, InviteTokens.newToken()).andExpect(status().isNotFound());

        assertThat(jdbc.queryForList("SELECT [outcome] FROM [login_attempts] WHERE [client_ip] = ? ORDER BY [id]", String.class, GUESSER))
                .containsExactly("UNKNOWN_USER", "UNKNOWN_USER", "UNKNOWN_USER", "RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT [rule_triggered] FROM [login_attempts] WHERE [client_ip] = ? AND [outcome] = 'RATE_LIMITED'",
                String.class, GUESSER)).isEqualTo("INVITE_IP");
        assertThat(output.getAll()).contains("LOGIN_PROTECTION_ALERT type=INVITE_IP");
        // No account is involved: nothing lands in user_auth_state.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_auth_state] WHERE [scope] = 'INVITE'", Integer.class)).isZero();
    }
}
