package com.beaconculinary.api.offline;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * O3: signs and verifies offline grants (contract section 5): a JWS (HS256) with header kid, signed
 * with pos.offline.grant-signing-key, a dedicated key that is never the JWT key. The grant is opaque
 * to the till.
 *
 * Verification checks the signature, kid and typ but not expiry: O4 must still read a grant that has
 * since expired or been revoked, to accept the sales made before. {@link Verified#expired(Instant)}
 * tells the caller whether it had expired at a given moment.
 */
@Component
public class OfflineGrantSigner {
    static final String TYPE = "offline-grant";
    private static final String TYPE_CLAIM = "typ";
    private static final String GRANT_CLAIM = "gid";
    private static final String DEVICE_CLAIM = "dev";
    private static final String CREDENTIAL_CLAIM = "cred";
    private static final String SHIFT_CLAIM = "shf";

    private final OfflineProperties properties;

    public OfflineGrantSigner(OfflineProperties properties) {
        this.properties = properties;
    }

    /** What a grant says. */
    public record Claimed(UUID grantId, UUID deviceId, UUID credentialId, long shiftId, long cashierId,
                          Instant issuedAt, Instant expiresAt) {
    }

    /** A grant whose signature, kid and typ are valid. */
    public record Verified(Claimed claims) {
        public boolean expired(Instant at) {
            return !at.isBefore(claims.expiresAt());
        }
    }

    public String sign(Claimed grant) {
        return Jwts.builder()
                .header().keyId(properties.getGrantKeyId()).and()
                .claim(TYPE_CLAIM, TYPE)
                .claim(GRANT_CLAIM, grant.grantId().toString())
                .claim(DEVICE_CLAIM, grant.deviceId().toString())
                .claim(CREDENTIAL_CLAIM, grant.credentialId().toString())
                .claim(SHIFT_CLAIM, grant.shiftId())
                .subject(Long.toString(grant.cashierId()))
                .issuedAt(Date.from(grant.issuedAt()))
                .expiration(Date.from(grant.expiresAt()))
                .signWith(key())
                .compact();
    }

    /** The grant's claims when it was signed by our current key; empty when tampered, foreign or malformed. */
    public Optional<Verified> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Claims claims;
        try {
            var jws = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            if (!properties.getGrantKeyId().equals(jws.getHeader().getKeyId())) {
                return Optional.empty();
            }
            claims = jws.getPayload();
        } catch (ExpiredJwtException e) {
            // Thrown only after the signature checked out; an expired grant is still a genuine one.
            if (!properties.getGrantKeyId().equals(e.getHeader().get("kid"))) {
                return Optional.empty();
            }
            claims = e.getClaims();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            if (!TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(new Verified(new Claimed(
                    UUID.fromString(claims.get(GRANT_CLAIM, String.class)),
                    UUID.fromString(claims.get(DEVICE_CLAIM, String.class)),
                    UUID.fromString(claims.get(CREDENTIAL_CLAIM, String.class)),
                    claims.get(SHIFT_CLAIM, Long.class),
                    Long.parseLong(claims.getSubject()),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private SecretKey key() {
        var bytes = properties.signingKeyBytes();
        if (bytes == null) {
            throw new IllegalStateException("pos.offline.grant-signing-key is not set");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
