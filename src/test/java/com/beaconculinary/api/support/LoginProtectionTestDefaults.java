package com.beaconculinary.api.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Turns login protection off for the test suite (WS1-1): the suite shares one persistent database
 * and logs in with the same seed accounts throughout, so recorded failures would build up.
 *
 * <p>Registered in src/test/resources/META-INF/spring.factories. The property source is placed just
 * after Spring's inlined test properties, so it wins over application.yaml, .env and environment
 * variables, while a test class can still opt in with
 * {@code @TestPropertySource(properties = "security.login-protection.mode=monitor")} or
 * {@code @SpringBootTest(properties = ...)}.
 */
public class LoginProtectionTestDefaults implements EnvironmentPostProcessor {
    static final String INLINED_TEST_PROPERTIES = "Inlined Test Properties";
    static final String SOURCE_NAME = "loginProtectionTestDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // WS1-1b: device trust is also pinned to its defaults, so a local .env (for example
        // SECURITY_DEVICETRUST_MODE=required for manual testing) doesn't change the suite.
        var source = new MapPropertySource(SOURCE_NAME, Map.of(
                "security.login-protection.mode", "off",
                "security.device-trust.mode", "off",
                "security.device-trust.multi-browser-enabled", "false",
                "security.device-trust.require-serial", "true",
                // O2: tests share one database and register tills freely, so the active-till limit is
                // pinned high; O2TillCodeIntegrationTests sets it back to 3. require-registered stays off.
                "pos.tills.max-active", "1000",
                "pos.tills.require-registered", "false",
                // O2: the session sweep runs on real time; tests move the clock, so they call it themselves.
                "pos.session.sweep-enabled", "false",
                // O3: offline stays off (the default) so a local .env can't turn it on; the O3 tests switch it
                // on themselves. The signing key is a fixed test-only value, not a secret.
                "pos.offline.enabled", "false",
                "pos.offline.grant-signing-key", "dGVzdC1vbmx5LW9mZmxpbmUtZ3JhbnQta2V5LW5vdC1hLXNlY3JldA=="));
        var sources = environment.getPropertySources();
        if (sources.contains(INLINED_TEST_PROPERTIES)) {
            sources.addAfter(INLINED_TEST_PROPERTIES, source);
        } else {
            sources.addFirst(source);
        }
    }
}
