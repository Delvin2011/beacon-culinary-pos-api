package com.beaconculinary.api.auth;

import com.beaconculinary.api.auth.protection.LoginAttempt;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.devices.DeviceGate;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.tills.TillException;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@AllArgsConstructor
@Service
public class AuthService {
    // A pre-computed BCrypt hash with no known matching plaintext. Comparing against this
    // when the cashierId doesn't exist keeps the matches() call on the same code path/timing
    // as a real user, so a wrong PIN and an unknown cashierId are indistinguishable to the caller.
    private static final String DUMMY_PIN_HASH = "$2a$10$2zAMRFEuq50y/bjUAAFpbO1K2wJjlSChL3EZPBO3FwvpR2HMhik7S";

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final DeviceGate deviceGate;
    private final Clock clock;
    private final SessionService sessionService;
    private final DemoAccountGuard demoGuard;

    public User getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = (Long) authentication.getPrincipal();

        return userRepository.findById(userId).orElse(null);
    }

    public User getCurrentUserOrNull() {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) return null;
            var principal = authentication.getPrincipal();
            if (!(principal instanceof Long)) return null;
            return userRepository.findById((Long) principal).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public LoginResponse login(LoginRequest request) {
        // Login protection: look the account up on every attempt, known or not, so the extra query
        // adds no timing difference. Skipped when protection and the demo guard are off.
        var account = loginAttempts.isActive() || demoGuard.blocking() ? userRepository.findByEmail(request.getEmail()).orElse(null) : null;
        var accountId = account == null ? null : account.getId();
        var attempt = loginAttempts.begin(LoginMethod.PASSWORD, LoginAttemptService.emailIdentifier(request.getEmail()), accountId);
        // T0-6a: a demo account is refused before the password is checked, with the same answer.
        if (demoGuard.refuses(account)) {
            loginAttempts.finish(attempt, LoginOutcome.DEMO_ACCOUNT, accountId);
            demoGuard.alertRefused("PASSWORD", accountId);
            throw new BadCredentialsException("Bad credentials");
        }
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    request.getEmail(),
                    request.getPassword()
                )
            );
        } catch (AuthenticationException e) {
            loginAttempts.finish(attempt, passwordFailureOutcome(e, account), accountId);
            throw e;
        }

        var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
        // O2: the session starts after the credential check; on a till it can still be refused (409).
        var signIn = SignIn.password(clock).withSession(startSession(user, SignIn.PASSWORD, attempt));
        var accessToken = jwtService.generateAccessToken(user, signIn);
        var refreshToken = jwtService.generateRefreshToken(user, signIn);
        loginAttempts.finish(attempt, LoginOutcome.SUCCESS, user.getId());

        return new LoginResponse(accessToken, refreshToken);
    }

    public LoginResponse pinLogin(PinLoginRequest request) {
        // Looked up first, for every attempt, so protection knows whether the cashier exists (known
        // cashiers have account state; unknown ids are tracked by id alone). Same query either way.
        var user = userRepository.findById(request.getCashierId()).orElse(null);
        var attempt = loginAttempts.begin(LoginMethod.PIN, String.valueOf(request.getCashierId()),
                user == null ? null : user.getId());
        // WS1-1b: after the lock/block check, cashier, admin and unknown IDs need a registered POS
        // till (required mode). Refused before the PIN check: no BCrypt, not a failed sign-in.
        if (deviceGate.refusesPinLogin(user)) {
            loginAttempts.finish(attempt, LoginOutcome.DEVICE_NOT_REGISTERED, user == null ? null : user.getId());
            throw DeviceException.notRegistered();
        }
        // T0-6a: a demo account is refused before the PIN is checked, with the same answer.
        if (demoGuard.refuses(user)) {
            loginAttempts.finish(attempt, LoginOutcome.DEMO_ACCOUNT, user.getId());
            demoGuard.alertRefused("PIN", user.getId());
            throw new BadCredentialsException("Invalid cashier ID or PIN");
        }
        var hashToCheck = user != null && user.getPinHash() != null ? user.getPinHash() : DUMMY_PIN_HASH;
        var matches = passwordEncoder.matches(request.getPin(), hashToCheck);

        if (user == null || !user.isActive() || !matches) {
            loginAttempts.finish(attempt, pinFailureOutcome(user), user == null ? null : user.getId());
            throw new BadCredentialsException("Invalid cashier ID or PIN");
        }

        var signIn = SignIn.pin(clock, deviceGate.deviceIdForToken()).withSession(startSession(user, SignIn.PIN, attempt));
        var accessToken = jwtService.generateAccessToken(user, signIn);
        var refreshToken = jwtService.generateRefreshToken(user, signIn);
        // D4 evidence only: whether the PIN is not 6 digits. The PIN itself is never stored or logged.
        loginAttempts.finish(attempt, LoginOutcome.SUCCESS, user.getId(), request.getPin().length() != 6);

        return new LoginResponse(accessToken, refreshToken);
    }

    private static LoginOutcome passwordFailureOutcome(AuthenticationException e, User account) {
        if (e instanceof DisabledException) {
            return LoginOutcome.DISABLED;
        }
        // Spring reports a PIN-only user (no password) as not found, like an unknown email.
        return account == null || account.getPassword() == null ? LoginOutcome.UNKNOWN_USER : LoginOutcome.BAD_CREDENTIAL;
    }

    private static LoginOutcome pinFailureOutcome(User user) {
        if (user == null) {
            return LoginOutcome.UNKNOWN_USER;
        }
        return user.isActive() ? LoginOutcome.BAD_CREDENTIAL : LoginOutcome.DISABLED;
    }

    public Jwt refreshAccessToken(String refreshToken) {
        var jwt = jwtService.parseToken(refreshToken);
        if (jwt == null || jwt.isExpired()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        var user = userRepository.findById(jwt.getUserId()).orElseThrow();
        // A deactivated user's existing refresh token stops working too, and so does a user whose
        // password or PIN sign-in is locked (enforce mode only).
        if (!user.isActive() || loginAttempts.isAccountLocked(user.getId())) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        // T0-6a: existing sessions of demo accounts stop at their next refresh.
        if (demoGuard.refuses(user)) {
            demoGuard.alertRefused("REFRESH", user.getId());
            throw new BadCredentialsException("Invalid refresh token");
        }
        // O2: the session must still be active (401 SESSION_ENDED with its reason otherwise).
        sessionService.requireActive(jwt.getSessionId());
        // Keeps how and when the person signed in (amr, auth_time, dev) and the session (sid).
        return jwtService.generateAccessToken(user, jwt.getSignIn());
    }

    /** O2: ends the session named by an access token (expired accepted, signature checked). */
    public void logout(String accessToken) {
        var jwt = jwtService.parseTokenAllowingExpired(accessToken);
        if (jwt != null) {
            sessionService.end(jwt.getSessionId(), SessionEndReason.LOGOUT);
        }
    }

    /** O2: starts the session after a correct credential. A till-rule refusal is recorded (never a
     * failure for lockout) and rethrown as its 409. */
    private UUID startSession(User user, String method, LoginAttempt attempt) {
        try {
            return sessionService.start(user, method);
        } catch (TillException e) {
            var outcome = "TILL_LOCKED".equals(e.getCode()) ? LoginOutcome.TILL_LOCKED : LoginOutcome.SHIFT_OPEN_ON_OTHER_TILL;
            loginAttempts.finish(attempt, outcome, user.getId());
            throw e;
        }
    }
}
