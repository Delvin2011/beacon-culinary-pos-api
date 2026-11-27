package com.beaconculinary.api.support.tenancy;

import com.beaconculinary.api.support.ClockTestConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * T1-2, TESTS ONLY: the base of multi-mode tests. One application context in multi-customer mode over
 * {@link MultiCustomerFixture} (customers alpha and bravo, at alpha.pos.localhost and bravo.pos.localhost), shared by
 * every subclass; requests pick their customer by their Host header (T1-3). The session sweep is off, as in the rest of
 * the suite.
 *
 * <p>T1-10: every subclass is in the cross-customer suite ({@link #TAG}; the tag is inherited), run on its own with
 * {@code mvnw test -Dgroups=cross-customer} and as check-all's "cross-customer tests" step. A multi-mode test that
 * doesn't extend this class carries the tag itself. docs/2026-11-27-cross-customer-tests.md
 */
@SpringBootTest(properties = {"app.tenancy.mode=multi", "pos.session.sweep-enabled=false",
        // T1-3: attempts are recorded (login protection observes, never blocks), and a status change reaches the
        // customer filter within 3 s. T1-4: a customer found behind is checked again after 2 s.
        "security.login-protection.mode=monitor", "app.tenancy.directory-cache-ttl=3s",
        "app.tenancy.customer-pool.schema-recheck=2s"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = MultiCustomerFixture.Initializer.class)
// T1-6: a clock tests can move (the jobs' timing, the schema recheck); the real time unless a test sets it, and reset after.
@Import(ClockTestConfig.class)
@Tag(MultiCustomerIntegrationTest.TAG)
public abstract class MultiCustomerIntegrationTest {
    /** T1-10: the cross-customer suite's tag. */
    public static final String TAG = "cross-customer";
}
