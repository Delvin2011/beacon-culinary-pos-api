package com.beaconculinary.api.auth;

import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.common.logging.LogMarkers;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.routing.FirstConnectionListener;
import com.beaconculinary.api.users.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * T0-6a: the seeded demo accounts (users.is_demo, V116) can't be used where real data lives.
 *
 * <p>When on, a demo account is refused by every way in: password and PIN sign-in, manager-PIN
 * approval, refresh (so existing sessions stop), change password, and invite links; it is also
 * left off the login tiles. The caller gets the same answer as a wrong credential. Each refusal
 * is recorded as outcome DEMO_ACCOUNT (never a failure towards lockout) and logged as
 * {@code LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNT_REFUSED}.
 *
 * <p>On: always under the prod profile, otherwise when security.demo-accounts.block-sign-in is
 * true (default false, so development and the tests can use the demo accounts).
 *
 * <p>Why block instead of refusing to start: removing the demo accounts without SQL needs a real
 * admin, who is set up through an invite link, which needs the app running.
 *
 * <p>T1-2: in multi-customer mode there is no customer at startup, so the count is reported per customer when its
 * database pool makes its first connection (a {@link FirstConnectionListener}), on that connection.
 */
@Slf4j
@Component
public class DemoAccountGuard implements FirstConnectionListener {
    private static final String COUNT_ACTIVE = "SELECT COUNT(*) FROM [users] WHERE [is_demo] = 1 AND [active] = 1";

    private final boolean blocking;
    private final boolean multiCustomer;
    private final JdbcTemplate jdbc;

    public DemoAccountGuard(Environment environment, JdbcTemplate jdbc,
                            @Value("${security.demo-accounts.block-sign-in:false}") boolean blockSignIn) {
        this.blocking = blockSignIn || environment.acceptsProfiles(Profiles.of("prod"));
        this.multiCustomer = "multi".equalsIgnoreCase(environment.getProperty("app.tenancy.mode", "single").trim());
        this.jdbc = jdbc;
    }

    /** Whether demo accounts are refused. */
    public boolean blocking() {
        return blocking;
    }

    /** Whether this user must be refused (a demo account while the guard is on). */
    public boolean refuses(User user) {
        return blocking && user != null && user.isDemo();
    }

    /** Logs the alert for a refused demo account. {@code path} names the way in (PASSWORD, PIN, REFRESH, ...). */
    public void alertRefused(String path, Long userId) {
        log.warn(LogMarkers.LOGIN_PROTECTION_ALERT, "{} type=DEMO_ACCOUNT_REFUSED path={} user={}",
                LoginAttemptService.ALERT_MARKER, path, userId == null ? "-" : userId);
    }

    /** One line at startup: how many active demo accounts exist, and whether they are blocked (single mode). */
    @EventListener(ApplicationReadyEvent.class)
    public void reportAtStartup() {
        if (multiCustomer) {
            log.info("Demo accounts: reported per customer at its database's first connection (multi-customer mode)");
            return;
        }
        long active;
        try {
            active = jdbc.queryForObject(COUNT_ACTIVE, Long.class);
        } catch (RuntimeException e) {
            log.error("Could not count demo accounts at startup", e);
            return;
        }
        report(active);
    }

    /** T1-2, multi mode: the same line for one customer, on its pool's first connection (tenantId is its slug). */
    @Override
    public void onFirstConnection(CurrentCustomer customer, Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(COUNT_ACTIVE)) {
            report(rs.next() ? rs.getLong(1) : 0);
        }
    }

    private void report(long active) {
        if (active == 0) {
            log.info("Demo accounts: none active; demo guard {}", blocking ? "on" : "off");
        } else if (blocking) {
            log.warn(LogMarkers.LOGIN_PROTECTION_ALERT, "{} type=DEMO_ACCOUNTS_ACTIVE count={} guard=on "
                    + "(sign-in refused; deactivate them: docs/2026-11-21-first-admin-and-invites.md)",
                    LoginAttemptService.ALERT_MARKER, active);
        } else {
            log.info("Demo accounts: {} active; demo guard off (development only)", active);
        }
    }
}
