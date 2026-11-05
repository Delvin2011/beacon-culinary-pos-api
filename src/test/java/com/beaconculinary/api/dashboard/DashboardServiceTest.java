package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Section isolation, caching and the layout's no-query rule, without a database. */
class DashboardServiceTest {
    private final AtomicInteger computed = new AtomicInteger();
    private DashboardService service;
    private User user;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-10-05T13:30:00Z"), ZoneOffset.UTC);
        var settings = new DashboardSettings(new MockEnvironment(), new BigDecimal("50.00"), new BigDecimal("3.0"),
                new BigDecimal("25"), "12:30", "06:00-15:00", true);
        var handlers = new ArrayList<SectionHandler>();
        for (var section : DashboardSection.values()) {
            handlers.add(new SectionHandler() {
                @Override
                public DashboardSection section() {
                    return section;
                }

                @Override
                public Result compute(Request request) {
                    computed.incrementAndGet();
                    if (section == DashboardSection.HEATMAP) {
                        throw new IllegalStateException("database timeout");
                    }
                    return new Result(Map.of("ok", true), null);
                }
            });
        }
        user = new User();
        user.setId(1L);
        user.setRole(Role.ADMIN);
        var auth = mock(AuthService.class);
        when(auth.getCurrentUser()).thenAnswer(invocation -> user);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new DashboardService(handlers, settings, new DashboardCache(clock, settings), auth, clock, transactions);
    }

    @Test
    void oneFailingSection_failsAlone() {
        assertThatThrownBy(() -> service.section("heatmap", Map.of()))
                .isInstanceOf(DashboardSectionFailedException.class);
        assertThat(service.section("today", Map.of()).data()).isEqualTo(Map.of("ok", true));
        assertThat(service.section("busy-hours", Map.of()).data()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void layout_runsNoSectionQuery() {
        assertThat(service.layout().sections()).hasSize(DashboardSection.values().length);
        assertThat(computed).hasValue(0);
    }

    @Test
    void cachedPerSectionRangeAndValueVisibility() {
        service.section("today", Map.of());
        service.section("today", Map.of());
        assertThat(computed).hasValue(1);
        service.section("today", Map.of("range", "month"));
        assertThat(computed).hasValue(2);
        user.setRole(Role.HEAD_CHEF);
        service.section("busy-hours", Map.of());
        user.setRole(Role.ADMIN);
        service.section("busy-hours", Map.of());
        assertThat(computed).hasValue(4);
    }

    @Test
    void permissionChecked_beforeRange() {
        user.setRole(Role.HEAD_CHEF);
        assertThatThrownBy(() -> service.section("today", Map.of("range", "bad"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.section("nope", Map.of())).isInstanceOf(DashboardSectionNotFoundException.class);
        user.setRole(Role.CASHIER);
        assertThatThrownBy(() -> service.layout()).isInstanceOf(AccessDeniedException.class);
    }
}
