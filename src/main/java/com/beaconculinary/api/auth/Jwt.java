package com.beaconculinary.api.auth;

import com.beaconculinary.api.users.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.util.Date;

public class Jwt {
    private final Claims claims;
    private final SecretKey secretKey;

    public Jwt(Claims claims, SecretKey secretKey) {
        this.claims = claims;
        this.secretKey = secretKey;
    }

    public boolean isExpired() {
        return claims.getExpiration().before(new Date());
    }

    public Long getUserId() {
        return Long.valueOf(claims.getSubject());
    }

    public Role getRole() {
        return Role.valueOf(claims.get("role", String.class));
    }

    /** How the person signed in (WS1-1b): {@link SignIn#PASSWORD}, {@link SignIn#PIN}, or null on older tokens. */
    public String getAmr() {
        return claims.get(SignIn.AMR_CLAIM, String.class);
    }

    /** When the person originally signed in (seconds since the epoch, UTC), or null on older tokens. */
    public Long getAuthTime() {
        var value = claims.get(SignIn.AUTH_TIME_CLAIM);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** The POS till the PIN sign-in came from (WS1-1b), or null. */
    public String getDeviceId() {
        return claims.get(SignIn.DEVICE_CLAIM, String.class);
    }

    /** The sign-in facts carried by this token, to keep them on refresh. */
    public SignIn getSignIn() {
        return new SignIn(getAmr(), getAuthTime(), getDeviceId());
    }

    public String toString() {
        return Jwts.builder().claims(claims).signWith(secretKey).compact();
    }
}
