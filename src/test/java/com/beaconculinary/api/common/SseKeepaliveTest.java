package com.beaconculinary.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T1-L0 follow-up: the keepalive setting. 0 schedules nothing; a negative interval is refused at start-up. */
class SseKeepaliveTest {

    @Test
    void zero_turnsTheKeepaliveOff() {
        var scheduler = scheduler();
        try {
            var keepalive = new SseKeepalive(scheduler, Duration.ZERO);
            assertThat(keepalive.enabled()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void aPositiveInterval_isScheduled_andCancelledOnShutdown() {
        var scheduler = scheduler();
        try {
            var keepalive = new SseKeepalive(scheduler, Duration.ofSeconds(25));
            assertThat(keepalive.enabled()).isTrue();
            assertThat(keepalive.interval()).isEqualTo(Duration.ofSeconds(25));
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
            keepalive.destroy();
            scheduler.getScheduledThreadPoolExecutor().purge();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void aNegativeInterval_isRefused() {
        var scheduler = scheduler();
        try {
            assertThatThrownBy(() -> new SseKeepalive(scheduler, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.sse.keepalive-interval");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void channelsWithNoStreams_sendNothing() {
        var scheduler = scheduler();
        try {
            var keepalive = new SseKeepalive(scheduler, Duration.ZERO);
            keepalive.channel("a");
            keepalive.channel("b");
            assertThat(keepalive.sendKeepalives()).isZero();
            assertThat(keepalive.openStreams()).isZero();
        } finally {
            scheduler.shutdown();
        }
    }

    private static ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
