package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LoginValidationAttemptAdvice is scoped to AuthController and AdminController and records only the
 * three login request types. Everything else keeps the response it had on main and records nothing.
 *
 * <p>Expected bodies are the literal output of GlobalExceptionHandler for these DTOs' constraint
 * messages; neither the handler nor the DTOs changed on this branch. Runs in monitor mode, so any
 * stray recording would show up as a new login_attempts row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.login-protection.mode=monitor")
class LoginValidationAdviceScopeIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    // ---- out of scope: unchanged and not recorded ----

    @Test
    void otherAdminController_validationError_isUnchanged_andNotRecorded() throws Exception {
        var before = attemptCount();
        var result = mockMvc.perform(post("/admin/catalog/categories").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo(Map.of("parentId", "parentId is required", "name", "name is required"));
        assertThat(attemptCount()).isEqualTo(before);
    }

    @Test
    void adminController_malformedJson_isUnchanged_andNotRecorded() throws Exception {
        var before = attemptCount();
        var result = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo(Map.of("error", "Invalid request body"));
        assertThat(attemptCount()).isEqualTo(before);
    }

    @Test
    void adminController_endpointWithoutValidation_isUnchanged_andNotRecorded() throws Exception {
        var before = attemptCount();
        var result = mockMvc.perform(get("/admin/hello").header("Authorization", "Bearer " + adminToken)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("Hello Admin!");
        assertThat(attemptCount()).isEqualTo(before);
    }

    // ---- in scope: unchanged response, one VALIDATION row ----

    @Test
    void authorizeSession_validationError_isUnchanged_andRecordedOnce() throws Exception {
        var before = validationCount("MANAGER_PIN");
        var result = mockMvc.perform(post("/admin/authorize-session").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"12\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo(Map.of("pin", "PIN must be 6 digits"));
        assertThat(validationCount("MANAGER_PIN")).isEqualTo(before + 1);
    }

    @Test
    void authorize_validationError_isUnchanged_andRecordedOnce() throws Exception {
        var before = validationCount("MANAGER_PIN");
        var result = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"abc\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo(Map.of("pin", "PIN must be 6 digits"));
        assertThat(validationCount("MANAGER_PIN")).isEqualTo(before + 1);
    }

    @Test
    void passwordLogin_validationError_isUnchanged_andRecordedOnce() throws Exception {
        var before = validationCount("PASSWORD");
        var result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"scope.check@example.test\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo(Map.of("password", "Password is required"));
        assertThat(validationCount("PASSWORD")).isEqualTo(before + 1);
    }

    private int attemptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts]", Integer.class);
    }

    private int validationCount(String method) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [outcome] = 'VALIDATION'",
                Integer.class, method);
    }

    private static Map<String, String> body(MvcResult result) throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }
}
