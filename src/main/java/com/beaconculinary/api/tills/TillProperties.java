package com.beaconculinary.api.tills;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * O2 till and POS-session settings ({@code pos.tills.*} and {@code pos.session.*}), validated at
 * startup. Each can be overridden by environment variable (for example POS_TILLS_MAXACTIVE). See
 * section 2 of docs/2026-11-17-o2-tills-shifts-api-contract.md. Other {@code pos.*} settings (such as
 * require-client-order-id) are read elsewhere and ignored here.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "pos")
public class TillProperties {
    @Valid
    private Tills tills = new Tills();

    @Valid
    private Session session = new Session();

    @Data
    public static class Tills {
        /** Most active tills at once; registering a new till beyond it is refused (TILL_LIMIT_REACHED). */
        @Min(1)
        private int maxActive = 3;

        /** When true, shift open, sales, adjustments and CASHIER/ADMIN PIN sign-in need a registered till. */
        private boolean requireRegistered = false;
    }

    @Data
    public static class Session {
        /** For the frontend: lock the till after this many minutes without user activity. */
        @Min(1)
        private int idleLockMinutes = 5;

        /** Server backstop: a POS session with no activity for this long ends with IDLE. */
        @Min(1)
        private int idleExpiryMinutes = 30;

        @AssertTrue(message = "pos.session.idle-expiry-minutes must be at least pos.session.idle-lock-minutes")
        public boolean isIdleExpiryAtLeastIdleLock() {
            return idleExpiryMinutes >= idleLockMinutes;
        }
    }
}
