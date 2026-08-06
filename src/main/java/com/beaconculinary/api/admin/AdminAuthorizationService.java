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
 * to approve a Stage 2.6 void/refund. The resulting token is short-lived and single-use.
 */
@Service
@AllArgsConstructor
public class AdminAuthorizationService {
    private static final long TOKEN_TTL_SECONDS = 60;

    private final UserRepository userRepository;
    private final AuthorizationTokenRepository authorizationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        var activeAdmins = userRepository.findByActiveTrueAndRoleIn(List.of(Role.ADMIN));

        var matchedAdmin = activeAdmins.stream()
                .filter(admin -> admin.getPinHash() != null && passwordEncoder.matches(request.getPin(), admin.getPinHash()))
                .findFirst()
                .orElseThrow(InvalidPinException::new);

        var token = new AuthorizationToken();
        token.setToken(generateToken());
        token.setAdmin(matchedAdmin);
        token.setExpiresAt(LocalDateTime.now(clock).plusSeconds(TOKEN_TTL_SECONDS));
        token.setUsed(false);
        authorizationTokenRepository.save(token);

        return new AuthorizeResponse(token.getToken(), matchedAdmin.getId(), matchedAdmin.getName(), token.getExpiresAt());
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

    private String generateToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
