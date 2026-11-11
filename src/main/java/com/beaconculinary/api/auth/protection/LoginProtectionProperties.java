package com.beaconculinary.api.auth.protection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * WS1-1 login protection settings ({@code security.login-protection.*}). Defaults follow decision D3.
 * Validated at startup: an invalid value, or {@code mode=enforce} before Phase 2, stops the app.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "security.login-protection")
public class LoginProtectionProperties {
    /** off | monitor | enforce. Defaults to off when unset; application.yaml sets monitor. */
    @NotNull
    private LoginProtectionMode mode = LoginProtectionMode.OFF;

    /** PASSWORD_ACCOUNT: failures per email. */
    @Valid @NotNull
    private Threshold passwordAccount = new Threshold(5, Duration.ofMinutes(15));

    /** PIN_PAIR: failures per cashierId and client IP. */
    @Valid @NotNull
    private Threshold pinPair = new Threshold(5, Duration.ofMinutes(15));

    /** PIN_ACCOUNT_CEILING: failures per cashierId from any source. */
    @Valid @NotNull
    private Threshold pinAccountCeiling = new Threshold(20, Duration.ofMinutes(60));

    /** MANAGER_PIN_CALLER: failed manager-PIN entries per calling user. */
    @Valid @NotNull
    private Threshold managerPinCaller = new Threshold(5, Duration.ofMinutes(15));

    /** MANAGER_PIN_GLOBAL: failed manager-PIN entries across all callers (alert only). */
    @Valid @NotNull
    private Threshold managerPinGlobal = new Threshold(20, Duration.ofMinutes(60));

    /** CHANGE_PASSWORD_USER: wrong old passwords per user. */
    @Valid @NotNull
    private Threshold changePasswordUser = new Threshold(5, Duration.ofMinutes(15));

    /** How long a BLOCK rule (PIN_PAIR, MANAGER_PIN_CALLER, CHANGE_PASSWORD_USER) blocks. */
    @NotNull
    private Duration blockDuration = Duration.ofMinutes(15);

    /** First and second lock durations for LOCK rules. The third lock needs an admin to unlock. */
    @NotNull
    @Size(min = 2, max = 2, message = "lock-durations must list exactly two durations (first and second lock)")
    private List<Duration> lockDurations = new ArrayList<>(List.of(Duration.ofMinutes(15), Duration.ofMinutes(60)));

    /** The lock level returns to 0 after a success, or after this long without a new lock. */
    @NotNull
    private Duration escalationReset = Duration.ofHours(24);

    /** Reverse proxies whose X-Forwarded-For is trusted: IP addresses or CIDR ranges. Empty = trust none. */
    @NotNull
    private List<String> trustedProxies = new ArrayList<>();

    /** login_attempts rows older than this are deleted daily (decision D5). */
    @Min(1)
    private int retentionDays = 90;

    /** Rows deleted per statement by the retention job. */
    @Min(100)
    private int retentionBatchSize = 5000;

    @AssertTrue(message = "security.login-protection.mode=enforce is not available until WS1-1 Phase 2; use off or monitor")
    public boolean isModeSupported() {
        return mode != LoginProtectionMode.ENFORCE;
    }

    @AssertTrue(message = "security.login-protection.trusted-proxies must contain IP addresses or CIDR ranges only")
    public boolean isTrustedProxiesValid() {
        return trustedProxies == null || trustedProxies.stream().allMatch(ClientIpResolver::isValidProxyEntry);
    }

    @AssertTrue(message = "security.login-protection durations must be positive")
    public boolean isDurationsPositive() {
        return positive(blockDuration) && positive(escalationReset)
                && (lockDurations == null || lockDurations.stream().allMatch(LoginProtectionProperties::positive));
    }

    public boolean isActive() {
        return mode != LoginProtectionMode.OFF;
    }

    private static boolean positive(Duration duration) {
        return duration == null || (!duration.isNegative() && !duration.isZero());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Threshold {
        /** The rule triggers once this many failures are already in the window (D3: 5 means the 6th attempt). */
        @Min(1)
        private int maxFailures;

        @NotNull
        private Duration window;
    }
}
