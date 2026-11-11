package com.beaconculinary.api.auth;

import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
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
        // adds no timing difference. Skipped when protection is off.
        var account = loginAttempts.isActive() ? userRepository.findByEmail(request.getEmail()).orElse(null) : null;
        var accountId = account == null ? null : account.getId();
        var attempt = loginAttempts.begin(LoginMethod.PASSWORD, LoginAttemptService.emailIdentifier(request.getEmail()), accountId);
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
        var accessToken = jwtService.generateAccessToken(user);
        var refreshToken = jwtService.generateRefreshToken(user);
        loginAttempts.finish(attempt, LoginOutcome.SUCCESS, user.getId());

        return new LoginResponse(accessToken, refreshToken);
    }

    public LoginResponse pinLogin(PinLoginRequest request) {
        var attempt = loginAttempts.begin(LoginMethod.PIN, String.valueOf(request.getCashierId()), request.getCashierId());
        var user = userRepository.findById(request.getCashierId()).orElse(null);
        var hashToCheck = user != null && user.getPinHash() != null ? user.getPinHash() : DUMMY_PIN_HASH;
        var matches = passwordEncoder.matches(request.getPin(), hashToCheck);

        if (user == null || !user.isActive() || !matches) {
            loginAttempts.finish(attempt, pinFailureOutcome(user), user == null ? null : user.getId());
            throw new BadCredentialsException("Invalid cashier ID or PIN");
        }

        var accessToken = jwtService.generateAccessToken(user);
        var refreshToken = jwtService.generateRefreshToken(user);
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
        // A deactivated user's existing refresh token stops working too.
        if (!user.isActive()) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        return jwtService.generateAccessToken(user);
    }
}
