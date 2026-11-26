package com.beaconculinary.api.support.tenancy;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * T1-2, TESTS ONLY: the base of multi-mode tests. One application context in multi-customer mode over
 * {@link MultiCustomerFixture} (customers alpha and bravo, at alpha.pos.localhost and bravo.pos.localhost), shared by
 * every subclass; requests pick their customer by their Host header (T1-3). The session sweep is off, as in the rest of
 * the suite.
 */
@SpringBootTest(properties = {"app.tenancy.mode=multi", "pos.session.sweep-enabled=false",
        // T1-3: attempts are recorded (login protection observes, never blocks), and a status change reaches the
        // customer filter within 3 s. T1-4: a customer found behind is checked again after 2 s.
        "security.login-protection.mode=monitor", "app.tenancy.directory-cache-ttl=3s",
        "app.tenancy.customer-pool.schema-recheck=2s"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = MultiCustomerFixture.Initializer.class)
public abstract class MultiCustomerIntegrationTest {
}
