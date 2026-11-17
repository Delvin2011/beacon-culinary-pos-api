package com.beaconculinary.api.offline;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O3: grant signing and verification (contract section 5). */
class OfflineGrantSignerTest {
    private OfflineProperties properties;
    private OfflineGrantSigner signer;
    private OfflineGrantSigner.Claimed grant;

    @BeforeEach
    void setUp() {
        properties = new OfflineProperties();
        properties.setGrantSigningKey(randomKey());
        signer = new OfflineGrantSigner(properties);
        var issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        grant = new OfflineGrantSigner.Claimed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 412L, 17L,
                issued, issued.plus(18, ChronoUnit.HOURS));
    }

    @Test
    void aSignedGrant_verifiesWithItsClaims() {
        var verified = signer.verify(signer.sign(grant));

        assertThat(verified).isPresent();
        assertThat(verified.get().claims()).isEqualTo(grant);
        assertThat(verified.get().expired(grant.issuedAt())).isFalse();
    }

    @Test
    void theGrantNamesItsKey() {
        var header = new String(Base64.getUrlDecoder().decode(signer.sign(grant).split("\\.")[0]), StandardCharsets.UTF_8);

        assertThat(header).contains("\"kid\":\"k1\"").contains("\"alg\":\"HS256\"");
    }

    @Test
    void aTamperedGrant_failsVerification() {
        var parts = signer.sign(grant).split("\\.");
        var payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"shf\":412", "\"shf\":413");
        var tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThat(payload).contains("\"shf\":413");
        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    void aGrantSignedWithAnotherKey_failsVerification() {
        var token = signer.sign(grant);
        properties.setGrantSigningKey(randomKey());

        assertThat(signer.verify(token)).isEmpty();
    }

    @Test
    void aGrantWithAnotherKeyId_failsVerification() {
        var token = signer.sign(grant);
        properties.setGrantKeyId("k2");

        assertThat(signer.verify(token)).isEmpty();
    }

    @Test
    void aTokenOfAnotherType_failsVerification() {
        var key = Keys.hmacShaKeyFor(properties.signingKeyBytes());
        var token = Jwts.builder().header().keyId("k1").and()
                .claim("typ", "access")
                .claim("gid", grant.grantId().toString()).claim("dev", grant.deviceId().toString())
                .claim("cred", grant.credentialId().toString()).claim("shf", 412L)
                .subject("17").issuedAt(new Date()).expiration(Date.from(grant.expiresAt()))
                .signWith(key).compact();

        assertThat(signer.verify(token)).isEmpty();
    }

    @Test
    void anExpiredGrant_stillVerifies_andReportsItsExpiry() {
        var issued = Instant.now().minus(20, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var old = new OfflineGrantSigner.Claimed(grant.grantId(), grant.deviceId(), grant.credentialId(), 412L, 17L,
                issued, issued.plus(18, ChronoUnit.HOURS));

        var verified = signer.verify(signer.sign(old));

        assertThat(verified).isPresent();
        assertThat(verified.get().claims()).isEqualTo(old);
        assertThat(verified.get().expired(Instant.now())).isTrue();
        assertThat(verified.get().expired(issued.plus(1, ChronoUnit.HOURS))).isFalse();
    }

    @Test
    void garbage_failsVerification() {
        assertThat(signer.verify(null)).isEmpty();
        assertThat(signer.verify("")).isEmpty();
        assertThat(signer.verify("not.a.grant")).isEmpty();
        assertThat(signer.verify("eyJhbGciOiJub25lIn0.eyJ0eXAiOiJvZmZsaW5lLWdyYW50In0.")).isEmpty();
    }

    @Test
    void withoutAKey_signingIsRefused() {
        properties.setGrantSigningKey(null);

        assertThatThrownBy(() -> signer.sign(grant)).isInstanceOf(IllegalStateException.class);
    }

    private static String randomKey() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
