package com.beaconculinary.api.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T0-1: JobContext sets jobName, runId and tenantId for a run, and puts the thread's MDC back. */
class JobContextTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(JobContext.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        MDC.clear();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        MDC.clear();
    }

    @Test
    void duringARun_theMdcHoldsTheJob_andAfterwardsIsEmptyAgain() {
        var seen = new AtomicReference<Map<String, String>>();

        JobContext.run("demo-job", () -> seen.set(MDC.getCopyOfContextMap()));

        assertThat(seen.get()).containsEntry("jobName", "demo-job").containsEntry("tenantId", "default");
        assertThat(seen.get().get("runId")).matches("[0-9a-f]{8}");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).startsWith("job demo-job finished durationMs=");
            assertThat(event.getMDCPropertyMap()).containsEntry("runId", seen.get().get("runId"));
        });
    }

    @Test
    void eachRun_getsItsOwnRunId() {
        var first = JobContext.call("demo-job", () -> MDC.get("runId"));
        var second = JobContext.call("demo-job", () -> MDC.get("runId"));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aFailingRun_isLoggedOnceAtError_rethrown_andTheMdcRestored() {
        MDC.put("requestId", "outer-request-1");

        assertThatThrownBy(() -> JobContext.run("demo-job", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("job demo-job failed");
            assertThat(event.getMDCPropertyMap()).containsEntry("jobName", "demo-job");
        });
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("requestId", "outer-request-1"));
    }
}
