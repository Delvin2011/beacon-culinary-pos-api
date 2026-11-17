package com.beaconculinary.api.tills;

import com.beaconculinary.api.auth.JwtService;
import com.beaconculinary.api.auth.RefreshCookies;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** O2 R9, R12: till status before sign-in, and locking the till. */
@RestController
@RequestMapping("/pos")
public class PosController {
    private final TillStatusService tillStatusService;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final RefreshCookies refreshCookies;

    public PosController(TillStatusService tillStatusService, SessionService sessionService, JwtService jwtService,
                         RefreshCookies refreshCookies) {
        this.tillStatusService = tillStatusService;
        this.sessionService = sessionService;
        this.jwtService = jwtService;
        this.refreshCookies = refreshCookies;
    }

    /** Public: the till comes from its deviceToken cookie. Details only for a registered till. */
    @GetMapping("/till-status")
    public TillStatusDto tillStatus() {
        return tillStatusService.status();
    }

    /**
     * Ends the caller's session (LOCK) and clears the refresh cookie. The shift stays open; only its
     * owner (resume) or an ADMIN can sign in on the till afterwards. Idempotent: always 204.
     */
    @PostMapping("/lock")
    public ResponseEntity<Void> lock(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                     HttpServletResponse response) {
        var jwt = authorization.startsWith("Bearer ") ? jwtService.parseToken(authorization.substring(7)) : null;
        if (jwt != null) {
            sessionService.end(jwt.getSessionId(), SessionEndReason.LOCK);
        }
        refreshCookies.clear(response);
        return ResponseEntity.noContent().build();
    }
}
