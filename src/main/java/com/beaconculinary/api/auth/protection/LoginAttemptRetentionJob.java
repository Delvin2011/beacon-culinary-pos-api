package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.common.UtcTimestamps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes login_attempts rows older than {@code retention-days} (decision D5), in batches so a large
 * backlog never holds a long lock. Runs daily at 03:30 business time. With more than one app
 * instance every instance runs it; the delete is idempotent, so that is harmless.
 */
@Slf4j
@Component
public class LoginAttemptRetentionJob {
    private static final int MAX_BATCHES_PER_RUN = 1000;

    private final LoginProtectionProperties properties;
    private final LoginProtectionStore store;
    private final Clock clock;

    public LoginAttemptRetentionJob(LoginProtectionProperties properties, LoginProtectionStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(cron = "${security.login-protection.retention-cron:0 30 3 * * *}", zone = "Africa/Johannesburg")
    public void scheduledPurge() {
        try {
            var deleted = purge();
            if (deleted > 0) {
                log.info("Login attempt retention: deleted {} rows older than {} days", deleted, properties.getRetentionDays());
            }
        } catch (RuntimeException e) {
            log.error("Login attempt retention failed", e);
        }
    }

    /** Deletes every row older than the retention period; returns how many were deleted. */
    public long purge() {
        var cutoff = UtcTimestamps.nowUtc(clock).minus(Duration.ofDays(properties.getRetentionDays()));
        var batchSize = properties.getRetentionBatchSize();
        long total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            var deleted = store.deleteAttemptsBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
