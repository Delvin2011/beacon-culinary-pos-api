package com.beaconculinary.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshCookiesTest {

    @Test
    void issueAndClear_shareEveryAttribute_inProdAndDevSettings() {
        for (var secure : new boolean[]{true, false}) {
            var cookies = new RefreshCookies(secure, "");
            var issued = cookies.build("value", Duration.ofDays(7));
            var cleared = cookies.build("", Duration.ZERO);
            assertSameAttributes(issued, cleared);
            assertThat(issued.isSecure()).isEqualTo(secure);
            assertThat(issued.getSameSite()).as("T1-5: Lax").isEqualTo("Lax");
            assertThat(issued.getPath()).isEqualTo("/auth/refresh");
            assertThat(issued.isHttpOnly()).isTrue();
            assertThat(issued.getDomain()).isNull();
        }
    }

    @Test
    void clear_writesOneEmptyCookieWithMaxAgeZero() {
        var response = new MockHttpServletResponse();
        new RefreshCookies(true, "").clear(response);
        var headers = response.getHeaders("Set-Cookie");
        assertThat(headers).hasSize(1);
        assertThat(headers.get(0)).startsWith("refreshToken=;").contains("Path=/auth/refresh").contains("Max-Age=0")
                .contains("Secure").contains("HttpOnly").contains("SameSite=Lax").doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void pathPrefix_putsTheProxyPrefixInFrontOfThePath_forIssueAndClear() {
        // T1-L0: behind a proxy that removes /api, the browser calls /api/auth/refresh.
        var cookies = new RefreshCookies(true, "/api");
        var issued = cookies.build("value", Duration.ofDays(7));
        var cleared = cookies.build("", Duration.ZERO);
        assertSameAttributes(issued, cleared);
        assertThat(issued.getPath()).isEqualTo("/api/auth/refresh");
        assertThat(new RefreshCookies(true, " /api ").build("v", Duration.ZERO).getPath()).isEqualTo("/api/auth/refresh");
        assertThat(new RefreshCookies(true, null).build("v", Duration.ZERO).getPath()).isEqualTo("/auth/refresh");
    }

    @Test
    void pathPrefix_refusesAValueThatWouldBreakThePath() {
        for (var bad : new String[]{"api", "/api/", "/", "/a pi", "/api;x", "//api"}) {
            assertThatThrownBy(() -> new RefreshCookies(true, bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cookie.path-prefix");
        }
    }

    private static void assertSameAttributes(ResponseCookie a, ResponseCookie b) {
        assertThat(b.getName()).isEqualTo(a.getName());
        assertThat(b.getPath()).isEqualTo(a.getPath());
        assertThat(b.getDomain()).isEqualTo(a.getDomain());
        assertThat(b.isSecure()).isEqualTo(a.isSecure());
        assertThat(b.isHttpOnly()).isEqualTo(a.isHttpOnly());
        assertThat(b.getSameSite()).isEqualTo(a.getSameSite());
    }
}
