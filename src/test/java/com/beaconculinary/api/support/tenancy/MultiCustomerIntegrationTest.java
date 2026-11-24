package com.beaconculinary.api.support.tenancy;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * T1-2, TESTS ONLY: the base of multi-mode tests. One application context in multi-customer mode over
 * {@link MultiCustomerFixture} (customers alpha and bravo), shared by every subclass, with the test-only customer header
 * ({@link TestCustomerConfig}). The session sweep is off, as in the rest of the suite.
 */
@SpringBootTest(properties = {"app.tenancy.mode=multi", "pos.session.sweep-enabled=false"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = MultiCustomerFixture.Initializer.class)
@Import(TestCustomerConfig.class)
public abstract class MultiCustomerIntegrationTest {
}
