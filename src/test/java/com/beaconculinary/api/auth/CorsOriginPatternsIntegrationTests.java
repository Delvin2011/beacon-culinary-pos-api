package com.beaconculinary.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-3: the allowed browser origins may be patterns, so one setting covers every customer's address. The browser sends
 * its Origin on a sign-in POST even on its own site, and behind the gateway the backend treats it as cross-origin: an
 * origin the setting doesn't cover is refused (403) before sign-in, one it covers reaches sign-in (here 401, a made-up
 * account). Exact addresses keep working (the other tests' http://localhost:3000).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "cors.allowed-origins=https://*.pos.localhost,http://localhost:3000")
class CorsOriginPatternsIntegrationTests {
    private static final String BODY = "{\"email\":\"nobody@example.test\",\"password\":\"not-a-password\"}";

    @Autowired
    private MockMvc mvc;

    @Test
    void everyCustomersAddress_matchesThePattern_andOthersAreRefused() throws Exception {
        for (var origin : new String[]{"https://beaconculinary.pos.localhost", "https://testcustomer.pos.localhost",
                "http://localhost:3000"}) {
            var response = signIn(origin);
            assertThat(response).as(origin + " reaches sign-in").isEqualTo(401);
        }
        for (var origin : new String[]{"https://evil.example", "http://testcustomer.pos.localhost",
                "https://pos.localhost.evil.example"}) {
            assertThat(signIn(origin)).as(origin + " is refused").isEqualTo(403);
        }
    }

    private int signIn(String origin) throws Exception {
        return mvc.perform(post("/auth/login").header("Origin", origin).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus();
    }
}
