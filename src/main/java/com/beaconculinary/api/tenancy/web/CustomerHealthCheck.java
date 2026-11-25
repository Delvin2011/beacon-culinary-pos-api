package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T1-3, multi mode: GET /health at a customer's address reports THAT customer's database, so each till's connection
 * indicator means something. A {@code SELECT 1} through the customer's own pool on a background thread, given at most
 * {@link #TIMEOUT}; the answer is kept for {@link #CACHE_FOR} per customer, so tills polling every few seconds cost at
 * most one query per customer per 5 seconds. Each customer has at most one probe running, and one slow database never
 * holds up another customer's answer. Logs once per customer when the answer changes.
 */
@Component
@ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
public class CustomerHealthCheck {
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration CACHE_FOR = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(CustomerHealthCheck.class);

    private final JdbcTemplate jdbcTemplate;
    private final Duration timeout;
    private final Duration cacheFor;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "health-customer-check");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong probes = new AtomicLong();

    @Autowired
    public CustomerHealthCheck(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TIMEOUT, CACHE_FOR);
    }

    CustomerHealthCheck(JdbcTemplate jdbcTemplate, Duration timeout, Duration cacheFor) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeout = timeout;
        this.cacheFor = cacheFor;
    }

    /** Whether this customer's database answers (cached for 5 s). */
    public boolean databaseUp(CurrentCustomer customer) {
        var state = states.computeIfAbsent(customer.id() + ":" + customer.slug(), key -> new State());
        synchronized (state) {
            if (state.lastUp != null && System.nanoTime() - state.lastCheckedNanos < cacheFor.toNanos()) {
                return state.lastUp;
            }
            if (state.inFlight == null || state.inFlight.isDone()) {
                probes.incrementAndGet();
                state.inFlight = executor.submit(() -> CustomerContext.callAs(customer,
                        () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class)));
            }
            boolean up;
            try {
                up = Integer.valueOf(1).equals(state.inFlight.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException | ExecutionException e) {
                up = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                up = false;
            }
            if (state.lastUp == null || state.lastUp != up) {
                if (up) {
                    log.atInfo().addKeyValue("customer", customer.slug()).log("Health: customer {} database reachable", customer.slug());
                } else {
                    log.atWarn().addKeyValue("customer", customer.slug())
                            .log("Health: customer {} database not reachable within {} ms", customer.slug(), timeout.toMillis());
                }
            }
            state.lastUp = up;
            state.lastCheckedNanos = System.nanoTime();
            return up;
        }
    }

    /** How many probes were run (tests). */
    long probes() {
        return probes.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class State {
        private Future<Integer> inFlight;
        private Boolean lastUp;
        private long lastCheckedNanos;
    }
}
