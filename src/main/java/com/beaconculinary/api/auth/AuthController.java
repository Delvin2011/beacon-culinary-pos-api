package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.UserDto;
import com.beaconculinary.api.users.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JwtConfig jwtConfig;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final RefreshCookies refreshCookies;

    public AuthController(
        JwtConfig jwtConfig,
        UserMapper userMapper,
        AuthService authService,
        RefreshCookies refreshCookies) {
        this.jwtConfig = jwtConfig;
        this.userMapper = userMapper;
        this.authService = authService;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/login")
    public JwtResponse login(
        @Valid @RequestBody LoginRequest request,
        HttpServletResponse response) {

        var loginResult = authService.login(request);
        addRefreshTokenCookie(response, loginResult.getRefreshToken().toString());

        return new JwtResponse(loginResult.getAccessToken().toString());
    }

    @PostMapping("/pin-login")
    public JwtResponse pinLogin(
        @Valid @RequestBody PinLoginRequest request,
        HttpServletResponse response) {

        var loginResult = authService.pinLogin(request);
        addRefreshTokenCookie(response, loginResult.getRefreshToken().toString());

        return new JwtResponse(loginResult.getAccessToken().toString());
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        refreshCookies.issue(response, refreshToken, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiration()));
    }

    /**
     * Removes the refresh cookie from this browser. Public and idempotent: works without a session or
     * with an expired access token, and always returns 204. The browser never sends the refresh cookie
     * here (its path is /auth/refresh), so the token itself isn't revoked. The deviceToken cookie is
     * left alone.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        refreshCookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    /** A missing cookie gets the same empty 401 as an invalid one (previously 400). */
    @PostMapping("/refresh")
    public JwtResponse refresh(@CookieValue(value = RefreshCookies.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }
        var accessToken = authService.refreshAccessToken(refreshToken);
        return new JwtResponse(accessToken.toString());
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me() {
        var user = authService.getCurrentUser();
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        var userDto = userMapper.toDto(user);
        return ResponseEntity.ok(userDto);
    }

    // A deactivated account (DisabledException) gets the same 401 as a wrong password.
    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public ResponseEntity<Void> handleBadCredentialsException() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
