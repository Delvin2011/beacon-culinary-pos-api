package com.beaconculinary.api.auth;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /auth/logout: public, idempotent, 204 with no body. It clears only the refreshToken cookie,
 * with the same attributes as when it is issued (prod and dev settings), and never the deviceToken.
 */
class AuthLogoutIntegrationTests {

    static List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie");
    }

    static String cookieHeader(MvcResult result, String name) {
        return setCookies(result).stream().filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
    }

    /** The attributes after the value, without Max-Age and Expires (which differ between issue and clear). */
    static List<String> attributes(String header) {
        return Arrays.stream(header.split(";\\s*")).skip(1)
                .filter(a -> !a.startsWith("Max-Age=") && !a.startsWith("Expires="))
                .sorted().toList();
    }

    static void assertCleared(MvcResult logout, MvcResult login, boolean secure) throws Exception {
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(logout.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(setCookies(logout)).as("exactly one Set-Cookie").hasSize(1);
        var cleared = cookieHeader(logout, RefreshCookies.NAME);
        assertThat(cleared).startsWith("refreshToken=;").contains("Max-Age=0").contains("Path=/auth/refresh")
                .contains("HttpOnly").doesNotContainIgnoringCase("Domain=");
        if (secure) {
            assertThat(cleared).contains("Secure").contains("SameSite=None");
        } else {
            assertThat(cleared).doesNotContain("Secure").contains("SameSite=Lax");
        }
        var issued = cookieHeader(login, RefreshCookies.NAME);
        assertThat(issued).as("login issues a refresh cookie").isNotNull();
        assertThat(attributes(cleared)).isEqualTo(attributes(issued));
    }

    abstract static class Base {
        @Autowired
        MockMvc mockMvc;

        MvcResult logout(String bearer, Cookie... cookies) throws Exception {
            var request = post("/auth/logout");
            if (bearer != null) {
                request = request.header("Authorization", "Bearer " + bearer);
            }
            if (cookies.length > 0) {
                request = request.cookie(cookies);
            }
            return mockMvc.perform(request).andReturn();
        }

        MvcResult passwordLogin() throws Exception {
            return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321")))).andReturn();
        }
    }

    /** Production settings (cookie.secure defaults to true). */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class Prod extends Base {
        @Autowired
        JwtConfig jwtConfig;

        @Test
        void withoutASession_returns204_andClearsTheRefreshCookieWithMatchingAttributes() throws Exception {
            assertCleared(logout(null), passwordLogin(), true);
        }

        @Test
        void withAnExpiredOrGarbageAccessToken_stillReturns204() throws Exception {
            var expired = Jwts.builder().subject("1").claim("role", "ADMIN")
                    .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                    .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                    .signWith(jwtConfig.getSecretKey()).compact();
            assertCleared(logout(expired), passwordLogin(), true);
            assertThat(logout("not-a-token").getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void callingItTwice_isHarmless() throws Exception {
            var login = passwordLogin();
            assertCleared(logout(AuthTestHelper.loginAsAdmin(mockMvc)), login, true);
            assertCleared(logout(null), login, true);
        }

        @Test
        void afterLogout_refreshWithoutTheCookie_is401WithNoBody() throws Exception {
            var refreshCookie = passwordLogin().getResponse().getCookie(RefreshCookies.NAME);
            assertThat(mockMvc.perform(post("/auth/refresh").cookie(refreshCookie)).andReturn().getResponse().getStatus())
                    .as("refresh works before logout").isEqualTo(200);

            logout(null);
            var refresh = mockMvc.perform(post("/auth/refresh")).andReturn();
            assertThat(refresh.getResponse().getStatus()).isEqualTo(401);
            assertThat(refresh.getResponse().getContentAsString()).isEmpty();
        }
    }

    /** Dev settings: not Secure, SameSite=Lax. */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "cookie.secure=false")
    class Dev extends Base {
        @Test
        void clearsWithTheDevAttributes() throws Exception {
            assertCleared(logout(null), passwordLogin(), false);
        }
    }

    /** The deviceToken cookie is never cleared or changed by logout. */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "security.device-trust.mode=required")
    class DeviceCookieUntouched extends Base {
        @Autowired
        JdbcTemplate jdbc;

        @AfterEach
        void removeTestTills() {
            jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE 'WS1b Logout %'");
            jdbc.update("DELETE FROM [devices] WHERE [name] LIKE 'WS1b Logout %'");
        }

        @Test
        void logout_leavesTheTillCookie_andTheTillStillWorks() throws Exception {
            var serial = "WS1BLOGOUT" + Long.toString(Math.abs(new SecureRandom().nextLong()), 36).toUpperCase();
            var registered = mockMvc.perform(post("/admin/devices")
                    .header("Authorization", "Bearer " + AuthTestHelper.loginAsAdmin(mockMvc))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(Map.of("name", "WS1b Logout till", "serialNumber", serial))))
                    .andReturn();
            assertThat(registered.getResponse().getStatus()).isEqualTo(201);
            var till = registered.getResponse().getCookie("deviceToken");
            assertThat(till).isNotNull();
            var refresh = passwordLogin().getResponse().getCookie(RefreshCookies.NAME);

            var result = logout(null, till, refresh);
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(cookieHeader(result, "deviceToken")).as("deviceToken not touched").isNull();
            assertThat(setCookies(result)).hasSize(1);

            assertThat(mockMvc.perform(get("/users/cashiers").cookie(till)).andReturn().getResponse().getStatus())
                    .as("the till still works after logout").isEqualTo(200);
        }
    }
}
