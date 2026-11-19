package com.beaconculinary.api.common;

import com.beaconculinary.api.common.logging.JobContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * O1: whether the database answers, for GET /health. A {@code SELECT 1} on a background thread,
 * given at most {@link #TIMEOUT} (a busy connection pool would otherwise hold the request for
 * Hikari's 30-second connection timeout), and the answer reused for {@link #CACHE_FOR}, so tills
 * polling every few seconds cost at most one query per 5 seconds. A probe still hanging from an
 * earlier call is waited on rather than joined by a second one. Logs once when the answer changes.
 */
@Slf4j
@Component
public class DatabaseHealthCheck {
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration CACHE_FOR = Duration.ofSeconds(5);

    private final JdbcTemplate jdbcTemplate;
    private final Duration timeout;
    private final Duration cacheFor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "health-db-check");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> inFlight;
    private volatile Boolean lastUp;
    private volatile long lastCheckedNanos;

    @Autowired
    public DatabaseHealthCheck(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TIMEOUT, CACHE_FOR);
    }

    DatabaseHealthCheck(JdbcTemplate jdbcTemplate, Duration timeout, Duration cacheFor) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeout = timeout;
        this.cacheFor = cacheFor;
    }

    public boolean databaseUp() {
        var cached = lastUp;
        if (cached != null && System.nanoTime() - lastCheckedNanos < cacheFor.toNanos()) {
            return cached;
        }
        synchronized (this) {
            if (lastUp != null && System.nanoTime() - lastCheckedNanos < cacheFor.toNanos()) {
                return lastUp;
            }
            var up = probe();
            if (lastUp == null || lastUp != up) {
                if (up) {
                    log.atInfo().addKeyValue("source", "health-check").log("Health: database reachable");
                } else {
                    log.atWarn().addKeyValue("source", "health-check")
                            .log("Health: database not reachable within {} ms", timeout.toMillis());
                }
            }
            lastUp = up;
            lastCheckedNanos = System.nanoTime();
            return up;
        }
    }

    private boolean probe() {
        if (inFlight == null || inFlight.isDone()) {
            // T0-1: anything logged on the health-db-check thread carries jobName=health-check.
            inFlight = executor.submit(() -> JobContext.call("health-check",
                    () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class)));
        }
        try {
            return Integer.valueOf(1).equals(inFlight.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
