package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@AllArgsConstructor
@Service
public class JwtService {
    private final JwtConfig jwtConfig;

    public Jwt generateAccessToken(User user) {
        return generateAccessToken(user, SignIn.NONE);
    }

    public Jwt generateRefreshToken(User user) {
        return generateRefreshToken(user, SignIn.NONE);
    }

    /** With the sign-in facts (WS1-1b): amr, auth_time and dev claims, each only when known. */
    public Jwt generateAccessToken(User user, SignIn signIn) {
        return generateToken(user, signIn, jwtConfig.getAccessTokenExpiration());
    }

    public Jwt generateRefreshToken(User user, SignIn signIn) {
        return generateToken(user, signIn, jwtConfig.getRefreshTokenExpiration());
    }

    private Jwt generateToken(User user, SignIn signIn, long tokenExpiration) {
        var builder = Jwts.claims()
                .subject(user.getId().toString())
                .add("email", user.getEmail())
                .add("name", user.getName())
                .add("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1000 * tokenExpiration));
        if (signIn.amr() != null) {
            builder.add(SignIn.AMR_CLAIM, signIn.amr());
        }
        if (signIn.authTime() != null) {
            builder.add(SignIn.AUTH_TIME_CLAIM, signIn.authTime());
        }
        if (signIn.deviceId() != null) {
            builder.add(SignIn.DEVICE_CLAIM, signIn.deviceId());
        }
        return new Jwt(builder.build(), jwtConfig.getSecretKey());
    }

    public Jwt parseToken(String token) {
        try {
            var claims = getClaims(token);
            return new Jwt(claims, jwtConfig.getSecretKey());
        } catch (JwtException e) {
            return null;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(jwtConfig.getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
