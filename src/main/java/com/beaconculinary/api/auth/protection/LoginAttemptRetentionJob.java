package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.tenancy.jobs.CustomerJobs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes login_attempts rows older than {@code retention-days} (decision D5), in batches so a large
 * backlog never holds a long lock. With more than one app instance every instance runs it; the
 * delete is idempotent, so that is harmless.
 * T1-2: in multi-customer mode it runs once per ACTIVE customer, in that customer's database
 * ({@link CustomerJobs}); a failure for one customer doesn't stop the others.
 * T1-6: daily at {@code retention-time} (03:30) in each customer's own time zone, plus its 0-20 minute
 * spread ({@link CustomerJobs#daily}), checked every minute. A late run (after a restart) and a missed
 * day (the app down) are both harmless: the next run deletes everything past the retention period.
 * The test suite turns the minute tick off ({@code retention-enabled=false}) and runs {@link #purgeWhenDue()}.
 */
@Slf4j
@Component
public class LoginAttemptRetentionJob {
    private static final int MAX_BATCHES_PER_RUN = 1000;

    private final LoginProtectionProperties properties;
    private final LoginProtectionStore store;
    private final Clock clock;
    private final CustomerJobs customerJobs;

    public LoginAttemptRetentionJob(LoginProtectionProperties properties, LoginProtectionStore store, Clock clock,
                                    CustomerJobs customerJobs) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
        this.customerJobs = customerJobs;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void scheduledPurge() {
        if (properties.isRetentionEnabled()) {
            purgeWhenDue();
        }
    }

    /** Runs the purge for each customer whose retention time has come today and that hasn't run it today. */
    public int purgeWhenDue() {
        // T0-1: every line of the run carries jobName and runId (T1-2: and the customer, in multi mode).
        return customerJobs.daily("login-attempt-retention", properties.getRetentionTime(), () -> {
            try {
                var deleted = purge();
                if (deleted > 0) {
                    log.info("Login attempt retention: deleted {} rows older than {} days", deleted, properties.getRetentionDays());
                }
            } catch (RuntimeException e) {
                log.error("Login attempt retention failed", e);
            }
        });
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
