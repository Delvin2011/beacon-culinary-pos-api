package com.beaconculinary.api.sessions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O2 R10: every minute, ends POS sessions idle for longer than pos.session.idle-expiry-minutes (IDLE)
 * and sessions past the refresh-token lifetime (EXPIRED). Until it runs, refresh and POS writes
 * already treat an idle session as ended (SessionService#requireActive), so the job only records it.
 * Switched off by pos.session.sweep-enabled=false (the test suite does, and calls the sweep itself).
 */
@Component
@ConditionalOnProperty(name = "pos.session.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class SessionSweepJob {
    private final SessionService sessionService;

    public SessionSweepJob(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweep() {
        sessionService.sweep();
    }
}
