package com.beaconculinary.api.auth;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.users.UserDetailsServiceImpl;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-3 (single mode): sign-in when the database fails answers 503 DATABASE_UNAVAILABLE with an ERROR line carrying the
 * request id, records no attempt, and never counts towards the burst limit or a lockout; wrong passwords and PINs answer
 * as before. Login protection is enforcing here, with a burst capacity of 5, so a counted failure would show.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"security.login-protection.mode=enforce", "security.login-protection.burst.capacity=5"})
class SignInUnavailableIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "654321";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoSpyBean
    private UserRepository userRepository;

    @AfterEach
    void restore() {
        Mockito.reset(userDetailsService, userRepository);
    }

    @Test
    void passwordSignIn_withTheDatabaseFailing_is503_recordsNothing_andNeverLocksOrLimits(CapturedOutput output) throws Exception {
        var email = "stockclerk@canteen.local"; // a password account (the kitchen user is PIN-only)
        var before = attempts(email);
        doThrow(new CannotGetJdbcConnectionException("test: database unavailable"))
                .when(userDetailsService).loadUserByUsername(email);

        for (int i = 0; i < 10; i++) {
            var response = signIn("/auth/login", Map.of("email", email, "password", PASSWORD), "t13-signin-pw-" + i + "-abc", "10.13.0.1");
            assertThat(response.getStatus()).as("try " + i).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"");
        }
        assertThat(output.getAll()).contains("Sign-in failed: the database is unavailable").contains("t13-signin-pw-0-abc");
        assertThat(attempts(email)).as("no attempt recorded").isEqualTo(before);

        Mockito.reset(userDetailsService);
        var ok = signIn("/auth/login", Map.of("email", email, "password", PASSWORD), "t13-signin-pw-ok-abc", "10.13.0.1");
        assertThat(ok.getStatus()).as("not locked out, not over the burst limit").isEqualTo(200);
    }

    @Test
    void pinSignIn_withTheDatabaseFailing_is503_andRecordsNothing() throws Exception {
        var cashierId = AuthTestHelper.cashierAId(mvc);
        var before = attempts(String.valueOf(cashierId));
        doThrow(new CannotGetJdbcConnectionException("test: database unavailable")).when(userRepository).findById(cashierId);

        for (int i = 0; i < 10; i++) {
            var response = signIn("/auth/pin-login", Map.of("cashierId", cashierId, "pin", "123456"), "t13-signin-pin-" + i + "-ab", "10.13.0.2");
            assertThat(response.getStatus()).as("try " + i).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"");
        }
        assertThat(attempts(String.valueOf(cashierId))).isEqualTo(before);
    }

    @Test
    void aWrongPasswordOrPin_answersAsBefore() throws Exception {
        var email = "stockadmin@canteen.local";
        var before = attempts(email);
        var wrongPassword = signIn("/auth/login", Map.of("email", email, "password", "not-the-password"), "t13-signin-wrong-pw", "10.13.0.3");
        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(attempts(email)).as("a real failure is still recorded").isEqualTo(before + 1);

        var cashierId = AuthTestHelper.cashierAId(mvc);
        var wrongPin = signIn("/auth/pin-login", Map.of("cashierId", cashierId, "pin", "999999"), "t13-signin-wrong-pin", "10.13.0.4");
        assertThat(wrongPin.getStatus()).isEqualTo(401);
    }

    private MockHttpServletResponse signIn(String path, Map<String, Object> body, String requestId, String clientIp) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body))
                        .header("X-Request-Id", requestId)
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        }))
                .andReturn().getResponse();
    }

    private int attempts(String identifier) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE identifier = ?", Integer.class, identifier);
    }
}
