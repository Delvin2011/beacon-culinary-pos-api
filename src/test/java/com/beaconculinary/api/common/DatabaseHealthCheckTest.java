package com.beaconculinary.api.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O1: the /health database check answers within its timeout, reuses its answer for the cache
 * period, and never stacks a second query on one that is still hanging. */
class DatabaseHealthCheckTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private DatabaseHealthCheck check;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (check != null) {
            check.shutdown();
        }
    }

    @Test
    void theCheckRunsAsAJob_andItsStateLineSaysWhereItComesFrom() {
        var jobName = new AtomicReference<String>();
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenAnswer(call -> {
            jobName.set(MDC.get("jobName"));
            return 1;
        });
        var logger = (Logger) LoggerFactory.getLogger(DatabaseHealthCheck.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            check = new DatabaseHealthCheck(jdbc, Duration.ofSeconds(2), Duration.ZERO);
            assertThat(check.databaseUp()).isTrue();
        } finally {
            logger.detachAppender(events);
        }

        assertThat(jobName.get()).isEqualTo("health-check");
        assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("source");
                    assertThat(pair.value).isEqualTo("health-check");
                }));
    }

    @Test
    void upWhenTheDatabaseAnswers() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        check = new DatabaseHealthCheck(jdbc, Duration.ofSeconds(2), Duration.ZERO);

        assertThat(check.databaseUp()).isTrue();
    }

    @Test
    void downWhenTheQueryFails() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new DataAccessResourceFailureException("no connection"));
        check = new DatabaseHealthCheck(jdbc, Duration.ofSeconds(2), Duration.ZERO);

        assertThat(check.databaseUp()).isFalse();
    }

    @Test
    void downWithinTheTimeoutWhenTheQueryHangs() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return 1;
        });
        check = new DatabaseHealthCheck(jdbc, Duration.ofMillis(200), Duration.ZERO);

        var started = System.nanoTime();
        assertThat(check.databaseUp()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void theAnswerIsReusedForTheCachePeriod_thenCheckedAgain() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        check = new DatabaseHealthCheck(jdbc, Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThat(check.databaseUp()).isTrue();
        assertThat(check.databaseUp()).isTrue();
        assertThat(check.databaseUp()).isTrue();
        verify(jdbc, times(1)).queryForObject("SELECT 1", Integer.class);

        Thread.sleep(400);
        assertThat(check.databaseUp()).isTrue();
        verify(jdbc, times(2)).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void aHangingQueryIsWaitedOn_notJoinedByASecondOne() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return 1;
        });
        check = new DatabaseHealthCheck(jdbc, Duration.ofMillis(100), Duration.ZERO);

        assertThat(check.databaseUp()).isFalse();
        assertThat(check.databaseUp()).isFalse();
        assertThat(check.databaseUp()).isFalse();
        verify(jdbc, times(1)).queryForObject("SELECT 1", Integer.class);

        // Once the database answers again, the next call reports it.
        release.countDown();
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        var up = false;
        while (!up && System.nanoTime() < deadline) {
            up = check.databaseUp();
            if (!up) {
                Thread.sleep(20);
            }
        }
        assertThat(up).isTrue();
    }
}
