package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.UserDto;
import com.beaconculinary.api.users.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JwtConfig jwtConfig;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final boolean cookieSecure;

    public AuthController(
        JwtConfig jwtConfig,
        UserMapper userMapper,
        AuthService authService,
        @Value("${cookie.secure:true}") boolean cookieSecure) {
        this.jwtConfig = jwtConfig;
        this.userMapper = userMapper;
        this.authService = authService;
        this.cookieSecure = cookieSecure;
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
        var cookie = ResponseCookie.from("refreshToken", refreshToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSecure ? "None" : "Lax")
            .path("/auth/refresh")
            .maxAge(Duration.ofSeconds(jwtConfig.getRefreshTokenExpiration()))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @PostMapping("/refresh")
    public JwtResponse refresh(@CookieValue(value = "refreshToken") String refreshToken) {
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

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Void> handleBadCredentialsException() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
