package com.beaconculinary.api.auth.protection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rest of the suite runs with the default test configuration: protection off, nothing recorded. */
@SpringBootTest
@AutoConfigureMockMvc
class LoginProtectionOffIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LoginProtectionProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void testSuiteDefault_isOff_andRecordsNothing() throws Exception {
        assertThat(properties.getMode()).isEqualTo(LoginProtectionMode.OFF);

        var email = "off-mode-" + System.nanoTime() + "@example.test";
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts] WHERE [identifier] = ?", Integer.class, email))
                .isZero();
    }
}
