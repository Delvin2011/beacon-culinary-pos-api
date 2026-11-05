package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.orders.OrderStatusStreamEvent;
import com.beaconculinary.api.orders.StationViewChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Each section's payload, kept for its refresh time. Keys are section + range + whether the
 * caller sees value fields (+ the section's own params, + the user for per-user sections). Ages
 * are measured on the application clock, so a moved clock (or one that goes backwards) expires
 * entries. A new order or a status change clears everything once committed (cheap: the sections
 * are recomputed on their next request); shift changes rely on the short lifetimes.
 */
@Component
public class DashboardCache {
    private static final int MAX_ENTRIES = 500;

    private final Clock clock;
    private final DashboardSettings settings;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public DashboardCache(Clock clock, DashboardSettings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public record Entry(Instant computedAt, Object value) {
    }

    public Entry get(String key, int ttlSeconds, Supplier<Object> compute) {
        if (!settings.cacheEnabled()) {
            return new Entry(clock.instant(), compute.get());
        }
        var now = clock.instant();
        var cached = entries.get(key);
        if (cached != null && !now.isBefore(cached.computedAt()) && now.isBefore(cached.computedAt().plusSeconds(ttlSeconds))) {
            return cached;
        }
        var fresh = new Entry(now, compute.get());
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(key, fresh);
        return fresh;
    }

    public void clear() {
        entries.clear();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onOrderStatus(OrderStatusStreamEvent event) {
        clear();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onStationView(StationViewChangedEvent event) {
        clear();
    }
}
