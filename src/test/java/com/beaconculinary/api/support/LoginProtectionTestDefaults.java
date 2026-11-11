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
        var source = new MapPropertySource(SOURCE_NAME, Map.of("security.login-protection.mode", "off"));
        var sources = environment.getPropertySources();
        if (sources.contains(INLINED_TEST_PROPERTIES)) {
            sources.addAfter(INLINED_TEST_PROPERTIES, source);
        } else {
            sources.addFirst(source);
        }
    }
}
