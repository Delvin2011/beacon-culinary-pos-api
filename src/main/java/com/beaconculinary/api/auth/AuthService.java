package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getEmail(),
                request.getPassword()
            )
        );

        var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
        var accessToken = jwtService.generateAccessToken(user);
        var refreshToken = jwtService.generateRefreshToken(user);

        return new LoginResponse(accessToken, refreshToken);
    }

    public LoginResponse pinLogin(PinLoginRequest request) {
        var user = userRepository.findById(request.getCashierId()).orElse(null);
        var hashToCheck = user != null && user.getPinHash() != null ? user.getPinHash() : DUMMY_PIN_HASH;
        var matches = passwordEncoder.matches(request.getPin(), hashToCheck);

        if (user == null || !user.isActive() || !matches) {
            throw new BadCredentialsException("Invalid cashier ID or PIN");
        }

        var accessToken = jwtService.generateAccessToken(user);
        var refreshToken = jwtService.generateRefreshToken(user);

        return new LoginResponse(accessToken, refreshToken);
    }

    public Jwt refreshAccessToken(String refreshToken) {
        var jwt = jwtService.parseToken(refreshToken);
        if (jwt == null || jwt.isExpired()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        var user = userRepository.findById(jwt.getUserId()).orElseThrow();
        return jwtService.generateAccessToken(user);
    }
}
