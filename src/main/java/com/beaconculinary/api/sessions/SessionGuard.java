package com.beaconculinary.api.sessions;

import com.beaconculinary.api.auth.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O2 R7: POS writes (sales, adjustments, mark-print-failed, shift open and close) need an active
 * session. One primary-key lookup per write, no cache, so a replaced, locked or force-closed session
 * stops at once. The session ID comes from the request's own bearer token (already verified by
 * JwtAuthenticationFilter). Called after the O1 replay lookup, so an ended session can still collect
 * the result of a write it already made.
 */
@Component
public class SessionGuard {
    private final JwtService jwtService;
    private final SessionService sessions;

    public SessionGuard(JwtService jwtService, SessionService sessions) {
        this.jwtService = jwtService;
        this.sessions = sessions;
    }

    /**
     * 401 SESSION_ENDED unless the caller's session is active. A call without a bearer token is an
     * internal one (no web request, or a test calling the service directly) and isn't checked: over
     * HTTP these endpoints authenticate only through the bearer token, so a request without one never
     * gets this far.
     */
    public void requireActive() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        var header = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return;
        }
        var jwt = jwtService.parseToken(header.substring(7));
        var session = sessions.requireActive(jwt == null ? null : jwt.getSessionId());
        // R10: a POS write is activity.
        sessions.touch(session);
    }
}
