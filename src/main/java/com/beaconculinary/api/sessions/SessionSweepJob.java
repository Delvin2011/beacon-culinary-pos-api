package com.beaconculinary.api.sessions;

import com.beaconculinary.api.tenancy.jobs.CustomerJobs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O2 R10: every minute, ends POS sessions idle for longer than pos.session.idle-expiry-minutes (IDLE)
 * and sessions past the refresh-token lifetime (EXPIRED). Until it runs, refresh and POS writes
 * already treat an idle session as ended (SessionService#requireActive), so the job only records it.
 * Switched off by pos.session.sweep-enabled=false (the test suite does, and calls the sweep itself).
 * T1-2: in multi-customer mode it runs once per ACTIVE customer, in that customer's database
 * ({@link CustomerJobs}); a failure for one customer doesn't stop the others.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pos.session.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class SessionSweepJob {
    private final SessionService sessionService;
    private final CustomerJobs customerJobs;

    public SessionSweepJob(SessionService sessionService, CustomerJobs customerJobs) {
        this.sessionService = sessionService;
        this.customerJobs = customerJobs;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweep() {
        // T0-1: logged with jobName and runId; INFO only when sessions were ended (it runs every minute).
        customerJobs.forEachActiveCustomer("session-sweep", () -> {
            var ended = sessionService.sweep();
            if (ended > 0) {
                log.info("Session sweep: ended {} sessions", ended);
            }
        });
    }
}
