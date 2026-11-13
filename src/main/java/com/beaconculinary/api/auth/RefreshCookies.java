package com.beaconculinary.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refreshToken cookie. Issuing and clearing share one builder, so the attributes (name, path,
 * Secure, HttpOnly, SameSite, no Domain) can't drift apart: a browser only removes a cookie when the
 * clearing Set-Cookie matches the one it holds.
 *
 * <p>{@code cookie.secure} (default true): Secure with SameSite=None, for a cross-site frontend.
 * The dev profile sets it to false: not Secure, SameSite=Lax.
 */
@Component
public class RefreshCookies {
    public static final String NAME = "refreshToken";
    /** The browser sends the cookie only to this path, so /auth/logout never receives it. */
    public static final String PATH = "/auth/refresh";

    private final boolean secure;

    public RefreshCookies(@Value("${cookie.secure:true}") boolean secure) {
        this.secure = secure;
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
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
