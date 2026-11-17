package com.beaconculinary.api.auth;

import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.devices.DeviceTrustProperties;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;

/**
 * A sensitive admin action needs a password sign-in no older than security.device-trust.reauth-max-age
 * (30 minutes; decision K2 of WS1-1b): managing tills, and since O2 force-closing a shift, recording a
 * late cash count and confirming there is none. Otherwise 403 REAUTH_REQUIRED.
 */
@Component
public class RecentPasswordCheck {
    private final JwtService jwtService;
    private final DeviceTrustProperties properties;
    private final UserRepository userRepository;
    private final Clock clock;

    public RecentPasswordCheck(JwtService jwtService, DeviceTrustProperties properties, UserRepository userRepository,
                               Clock clock) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** The signed-in user, when they signed in with a password recently enough; {@code action} completes "Sign in again with your password to …". */
    public User require(String action) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw refused(action);
        }
        return require(attributes.getRequest(), action);
    }

    public User require(HttpServletRequest http, String action) {
        var header = http.getHeader(HttpHeaders.AUTHORIZATION);
        var jwt = header != null && header.startsWith("Bearer ") ? jwtService.parseToken(header.substring(7)) : null;
        var authTime = jwt == null ? null : jwt.getAuthTime();
        var fresh = jwt != null && SignIn.PASSWORD.equals(jwt.getAmr()) && authTime != null
                && clock.instant().getEpochSecond() - authTime <= properties.getReauthMaxAge().toSeconds();
        if (!fresh) {
            throw refused(action);
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = authentication != null && authentication.getPrincipal() instanceof Long id ? id : jwt.getUserId();
        return userRepository.findById(userId).orElseThrow(() -> refused(action));
    }

    private static DeviceException refused(String action) {
        return new DeviceException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Sign in again with your password to " + action + ".");
    }
}
