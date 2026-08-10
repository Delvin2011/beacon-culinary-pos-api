package com.beaconculinary.api.admin;

import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Manager-PIN authorization, decoupled from the cashier's own login session — the cashier
 * stays logged in at the till, and whichever ADMIN is physically present enters their own PIN
 * to approve a Stage 2.6 void/refund (or, per Stage 3.3, to open a longer-lived management
 * session). The single-use token is short-lived; the session token is longer-lived and reusable.
 */
@Service
@AllArgsConstructor
public class AdminAuthorizationService {
    private static final long TOKEN_TTL_SECONDS = 60;
    private static final long SESSION_TTL_SECONDS = 300;

    private final UserRepository userRepository;
    private final AuthorizationTokenRepository authorizationTokenRepository;
    private final AuthorizationSessionRepository authorizationSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        var matchedAdmin = matchAdmin(request.getPin());

        var token = new AuthorizationToken();
        token.setToken(generateToken());
        token.setAdmin(matchedAdmin);
        token.setExpiresAt(LocalDateTime.now(clock).plusSeconds(TOKEN_TTL_SECONDS));
        token.setUsed(false);
        authorizationTokenRepository.save(token);

        return new AuthorizeResponse(token.getToken(), matchedAdmin.getId(), matchedAdmin.getName(), token.getExpiresAt());
    }

    /**
     * Stage 3.3 — opens a longer-lived, reusable session for the cashier-terminal management
     * menu: Void/Refund/Discount and a read-only Cashup Summary, without re-prompting for a PIN
     * on every individual action within the window.
     */
    @Transactional
    public AuthorizeSessionResponse authorizeSession(AuthorizeRequest request) {
        var matchedAdmin = matchAdmin(request.getPin());

        var session = new AuthorizationSession();
        session.setSessionToken(generateToken());
        session.setAdmin(matchedAdmin);
        session.setExpiresAt(LocalDateTime.now(clock).plusSeconds(SESSION_TTL_SECONDS));
        authorizationSessionRepository.save(session);

        return new AuthorizeSessionResponse(session.getSessionToken(), matchedAdmin.getId(), session.getExpiresAt());
    }

    /**
     * Validates and burns a token in its own transaction, committed independently of the
     * caller's (REQUIRES_NEW): if the adjustment this token is authorizing fails validation
     * later in the caller's transaction and that transaction rolls back, the token must stay
     * burned regardless — forcing a fresh PIN entry on retry, rather than making the token
     * resumable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User consumeToken(String tokenValue) {
        var token = authorizationTokenRepository.findByToken(tokenValue).orElse(null);
        var now = LocalDateTime.now(clock);

        if (token == null || token.isUsed() || token.getExpiresAt().isBefore(now)) {
            throw new InvalidAuthorizationTokenException();
        }

        var admin = token.getAdmin();
        authorizationTokenRepository.delete(token);
        return admin;
    }

    /**
     * Stage 3.3 — validates a session token without consuming it: it stays valid for further
     * calls until its own expiry, unlike {@link #consumeToken}.
     */
    @Transactional(readOnly = true)
    public User validateSessionToken(String tokenValue) {
        var session = authorizationSessionRepository.findBySessionToken(tokenValue).orElse(null);
        var now = LocalDateTime.now(clock);

        if (session == null || session.getExpiresAt().isBefore(now)) {
            throw new InvalidAuthorizationTokenException();
        }
        return session.getAdmin();
    }

    private User matchAdmin(String pin) {
        var activeAdmins = userRepository.findByActiveTrueAndRoleIn(List.of(Role.ADMIN));

        return activeAdmins.stream()
                .filter(admin -> admin.getPinHash() != null && passwordEncoder.matches(pin, admin.getPinHash()))
                .findFirst()
                .orElseThrow(InvalidPinException::new);
    }

    private String generateToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
