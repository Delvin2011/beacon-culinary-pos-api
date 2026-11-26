package com.beaconculinary.api.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beaconculinary.api.auth.protection.LoginAttemptRetentionJob;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.devices.DeviceAlerts;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.sessions.SessionSweepJob;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.tenancy.jobs.CustomerJobs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-1 step 3: scheduled jobs log with jobName and runId; alert lines carry their marker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class BackgroundLoggingIntegrationTests {
    @Autowired private LoginAttemptRetentionJob retentionJob;
    @Autowired private SessionService sessionService;
    @Autowired private CustomerJobs customerJobs;
    @Autowired private LoginAttemptService loginAttemptService;
    @Autowired private DeviceAlerts deviceAlerts;
    @Autowired private MutableClock clock;

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final List<Logger> watched = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();

    @BeforeEach
    void attach() {
        MDC.clear();
        events.start();
        for (var type : List.of(JobContext.class, SessionSweepJob.class, LoginAttemptRetentionJob.class,
                LoginAttemptService.class, DeviceAlerts.class)) {
            var logger = (Logger) LoggerFactory.getLogger(type);
            watched.add(logger);
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.DEBUG);
            logger.addAppender(events);
        }
    }

    @AfterEach
    void detach() {
        for (int i = 0; i < watched.size(); i++) {
            watched.get(i).detachAppender(events);
            watched.get(i).setLevel(previousLevels.get(i));
        }
    }

    private List<ILoggingEvent> forJob(String jobName) {
        return events.list.stream().filter(e -> jobName.equals(e.getMDCPropertyMap().get("jobName"))).toList();
    }

    @Test
    void theLoginAttemptRetentionJob_logsWithItsJobNameAndARunId() {
        // T1-6: 04:00 in Johannesburg, after the daily 03:30 (the minute tick is off in tests; run it ourselves).
        clock.setInstant(Instant.parse("2031-03-01T02:00:00Z"));
        try {
            retentionJob.purgeWhenDue();
        } finally {
            clock.reset();
        }

        assertThat(forJob("login-attempt-retention")).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getMDCPropertyMap().get("runId")).matches("[0-9a-f]{8}");
            assertThat(event.getMDCPropertyMap()).containsEntry("tenantId", "default");
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void theSessionSweepJob_logsWithItsJobNameAndARunId() {
        // The scheduled bean is off in tests (pos.session.sweep-enabled=false); run the job itself.
        new SessionSweepJob(sessionService, customerJobs).sweep();

        assertThat(forJob("session-sweep")).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap().get("runId")).matches("[0-9a-f]{8}"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void alertLines_carryTheMarker_andKeepTheMarkerAsTheFirstWord() {
        loginAttemptService.selfUnlockRefused(987654321L, "T01_TEST");
        deviceAlerts.alert("T01_TEST", "device=test");

        var alerts = events.list.stream().filter(e -> e.getFormattedMessage().contains("T01_TEST")).toList();
        assertThat(alerts).hasSize(2).allSatisfy(event -> {
            assertThat(event.getMarkerList()).contains(LogMarkers.LOGIN_PROTECTION_ALERT);
            assertThat(event.getFormattedMessage()).startsWith("LOGIN_PROTECTION_ALERT type=");
        });
    }
}
