package com.beaconculinary.api.common;

import com.beaconculinary.api.dashboard.DashboardCache;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.settings.SiteSettingsFixture;
import com.beaconculinary.api.settings.SiteSettingsStore;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-2: in-memory customer data is kept per customer, so nothing one customer sees is served to another: the open
 * SSE streams (kitchen, bar, board channels), the site settings cache and the dashboard cache. With no customer (single
 * mode) everything behaves as before.
 */
class CustomerScopedStateTest {
    private static final CurrentCustomer A = new CurrentCustomer(1, "customera");
    private static final CurrentCustomer B = new CurrentCustomer(2, "customerb");

    @Test
    void anEvent_reachesOnlyTheCurrentCustomersStreams_theKeepaliveReachesAll() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            var channel = new SseKeepalive(scheduler, Duration.ZERO).channel("kitchen");
            CustomerContext.runAs(A, channel::subscribe);
            CustomerContext.runAs(A, channel::subscribe);
            CustomerContext.runAs(B, channel::subscribe);
            channel.subscribe(); // no customer: single mode

            assertThat(CustomerContext.callAs(A, () -> channel.broadcast("order for A"))).isEqualTo(2);
            assertThat(CustomerContext.callAs(B, () -> channel.broadcast("order for B"))).isEqualTo(1);
            assertThat(channel.broadcast("single mode")).isEqualTo(1);
            assertThat(CustomerContext.callAs(new CurrentCustomer(3, "nostreams"), () -> channel.broadcast("x"))).isZero();
            assertThat(channel.openStreams()).isEqualTo(4);
            assertThat(channel.keepalive()).as("the keepalive goes to every open stream").isEqualTo(4);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void siteSettings_areCachedPerCustomer() {
        var store = new PerCustomerStore();
        store.saved.put("1:customera", "15");
        store.saved.put("2:customerb", "40");
        var settings = SiteSettingsFixture.settings(store, Clock.systemUTC());

        assertThat(CustomerContext.callAs(A, settings::boardDoneDisplayMinutes)).isEqualTo(15);
        assertThat(CustomerContext.callAs(B, settings::boardDoneDisplayMinutes)).isEqualTo(40);
        assertThat(CustomerContext.callAs(A, settings::boardDoneDisplayMinutes)).as("A's own cached value").isEqualTo(15);
        assertThat(store.reads).as("one read per customer, then the cache").containsEntry("1:customera", 1).containsEntry("2:customerb", 1);

        settings.invalidate();
        assertThat(CustomerContext.callAs(B, settings::boardDoneDisplayMinutes)).isEqualTo(40);
        assertThat(store.reads).containsEntry("2:customerb", 2);
    }

    @Test
    void theDashboardCache_neverServesOneCustomersFiguresToAnother() {
        var cache = new DashboardCache(Clock.systemUTC(),
                new DashboardSettings(new MockEnvironment(), SiteSettingsFixture.settings(), true));
        assertThat(CustomerContext.callAs(A, () -> cache.get("today", 60, () -> "A's figures").value())).isEqualTo("A's figures");
        assertThat(CustomerContext.callAs(B, () -> cache.get("today", 60, () -> "B's figures").value())).isEqualTo("B's figures");
        assertThat(CustomerContext.callAs(A, () -> cache.get("today", 60, () -> "recomputed").value()))
                .as("A's entry is still cached").isEqualTo("A's figures");
        assertThat(cache.get("today", 60, () -> "single mode").value()).isEqualTo("single mode");
    }

    /** A settings store that answers differently per customer and counts reads per customer. */
    private static final class PerCustomerStore extends SiteSettingsFixture.FakeStore {
        final Map<String, String> saved = new HashMap<>();
        final Map<String, Integer> reads = new HashMap<>();

        @Override
        public List<SiteSettingsStore.Saved> findAll() {
            var key = CustomerContext.scopeKey();
            reads.merge(key, 1, Integer::sum);
            var value = saved.get(key);
            return value == null ? List.of() : List.of(new SiteSettingsStore.Saved("board.done-display-minutes", value,
                    LocalDateTime.of(2026, 11, 25, 8, 0), "Admin"));
        }
    }
}
