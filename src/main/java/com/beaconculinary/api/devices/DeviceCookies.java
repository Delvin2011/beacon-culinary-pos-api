package com.beaconculinary.api.devices;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The deviceToken cookie: HttpOnly, Secure (except local development), SameSite=None when secure
 * and Lax otherwise (as the refresh cookie), Path=/, Max-Age = cookie-max-age-days.
 */
@Component
public class DeviceCookies {
    public static final String NAME = "deviceToken";

    private final DeviceTrustProperties properties;
    private final boolean secure;

    public DeviceCookies(DeviceTrustProperties properties, @Value("${cookie.secure:true}") boolean secure) {
        this.properties = properties;
        this.secure = secure;
    }

    public void write(HttpServletResponse response, String token) {
        var cookie = ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .path("/")
                .maxAge(Duration.ofDays(properties.getCookieMaxAgeDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** The token from the request's deviceToken cookie, or null. */
    public static String read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (var cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
