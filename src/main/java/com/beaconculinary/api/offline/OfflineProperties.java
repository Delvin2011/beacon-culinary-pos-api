package com.beaconculinary.api.offline;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Base64;

/**
 * O3 offline settings ({@code pos.offline.*}), validated at startup; each can be overridden by
 * environment variable (for example POS_OFFLINE_ENABLED). Section 2 of
 * docs/2026-11-17-o3-offline-foundation-api-contract.md.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "pos.offline")
public class OfflineProperties {
    static final int MIN_KEY_BYTES = 32;

    /** Master switch: off, grant and snapshot are refused (OFFLINE_DISABLED); status reports still count. */
    private boolean enabled = false;

    @Min(1)
    private int maxOfflineHours = 6;

    @Min(1)
    private int warnOfflineHours = 5;

    private boolean accountsEnabled = true;

    /** Most that may be charged to one account, on one till, during one outage. */
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal accountCap = new BigDecimal("150.00");

    /** How long a grant lasts: a full shift plus an outage. */
    @Min(1)
    private int grantMaxHours = 18;

    /** Silence for longer than this makes a till's status UNKNOWN. */
    @Min(2)
    private int statusStaleMinutes = 10;

    /** Base64 HMAC key for grants: a dedicated secret, never the JWT key. Required when enabled. */
    private String grantSigningKey;

    @NotBlank
    @Size(max = 20)
    private String grantKeyId = "k1";

    @AssertTrue(message = "pos.offline.warn-offline-hours must be less than pos.offline.max-offline-hours")
    public boolean isWarnBeforeMax() {
        return warnOfflineHours < maxOfflineHours;
    }

    @AssertTrue(message = "pos.offline.grant-max-hours must be at least pos.offline.max-offline-hours")
    public boolean isGrantLongEnough() {
        return grantMaxHours >= maxOfflineHours;
    }

    @AssertTrue(message = "pos.offline.grant-signing-key must be Base64 of at least 32 bytes when pos.offline.enabled is true")
    public boolean isSigningKeyValidWhenEnabled() {
        return !enabled || signingKeyBytes() != null;
    }

    /** The decoded signing key, or null when it's missing, not Base64 or too short. */
    public byte[] signingKeyBytes() {
        if (grantSigningKey == null || grantSigningKey.isBlank()) {
            return null;
        }
        try {
            var bytes = Base64.getDecoder().decode(grantSigningKey.trim());
            return bytes.length >= MIN_KEY_BYTES ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        // Never print the signing key.
        return "OfflineProperties(enabled=" + enabled + ", maxOfflineHours=" + maxOfflineHours
                + ", warnOfflineHours=" + warnOfflineHours + ", accountsEnabled=" + accountsEnabled
                + ", accountCap=" + accountCap + ", grantMaxHours=" + grantMaxHours
                + ", statusStaleMinutes=" + statusStaleMinutes + ", grantKeyId=" + grantKeyId + ")";
    }
}
