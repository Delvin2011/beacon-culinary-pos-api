package com.beaconculinary.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The refreshToken cookie. Issuing and clearing share one builder, so the attributes (name, path,
 * Secure, HttpOnly, SameSite, no Domain) can't drift apart: a browser only removes a cookie when the
 * clearing Set-Cookie matches the one it holds.
 *
 * <p>{@code cookie.secure} (default true): Secure with SameSite=None, for a cross-site frontend.
 * The dev profile sets it to false: not Secure, SameSite=Lax.
 *
 * <p>{@code cookie.path-prefix} (default empty): the path the browser sees in front of this API when a
 * proxy removes it before the request reaches the backend (T1-L0: the local stack and Front Door send
 * /api/* here without /api). The cookie path is then /api/auth/refresh, the address the browser calls;
 * without it the browser never sends the cookie back and refresh fails.
 */
@Component
public class RefreshCookies {
    public static final String NAME = "refreshToken";
    /** The browser sends the cookie only to this path, so /auth/logout never receives it. */
    public static final String PATH = "/auth/refresh";

    /** Empty, or "/" followed by path segments of letters, digits, "-" and "_", with no trailing "/". */
    private static final Pattern PREFIX = Pattern.compile("(/[A-Za-z0-9_-]+)*");

    private final boolean secure;
    private final String path;

    public RefreshCookies(@Value("${cookie.secure:true}") boolean secure,
                          @Value("${cookie.path-prefix:}") String pathPrefix) {
        var prefix = pathPrefix == null ? "" : pathPrefix.trim();
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "cookie.path-prefix must be empty or like /api (a leading /, no trailing /), was: " + prefix);
        }
        this.secure = secure;
        this.path = prefix + PATH;
    }

    /** Sets the refresh token cookie. */
    public void issue(HttpServletResponse response, String refreshToken, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(refreshToken, maxAge).toString());
    }

    /** Removes the refresh token cookie from the browser: same attributes, empty value, Max-Age=0. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
