package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
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
        if (signIn.sessionId() != null) {
            builder.add(SignIn.SESSION_CLAIM, signIn.sessionId());
        }
        // T1-5: the customer it is issued at (multi mode); single mode adds nothing.
        var customerId = TenantBinding.currentCustomerId();
        if (customerId != null) {
            builder.add(TenantBinding.CLAIM, customerId);
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

    /** O2 logout: a correctly signed token's claims even when it has expired; null when the signature or format is wrong. */
    public Jwt parseTokenAllowingExpired(String token) {
        try {
            return new Jwt(getClaims(token), jwtConfig.getSecretKey());
        } catch (ExpiredJwtException e) {
            return new Jwt(e.getClaims(), jwtConfig.getSecretKey());
        } catch (JwtException | IllegalArgumentException e) {
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
