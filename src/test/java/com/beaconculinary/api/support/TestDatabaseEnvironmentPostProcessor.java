package com.beaconculinary.api.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Locale;
import java.util.Map;

/**
 * Keeps the test suite off real data. The tests wipe orders, shifts and accounts, so they always
 * run against their own database: TEST_DATASOURCE_URL when set, otherwise BeaconculinaryTest on
 * the local server. Username and password still come from SPRING_DATASOURCE_USERNAME/PASSWORD.
 *
 * <p>Registered in src/test/resources/META-INF/spring.factories, so it exists only on the test
 * classpath, and its property source goes first: it wins over SPRING_DATASOURCE_URL from .env,
 * which as an environment variable would otherwise beat any test config file. A URL whose
 * database name doesn't contain "test" is refused before anything connects.
 */
public class TestDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String DEFAULT_URL =
            "jdbc:sqlserver://localhost:1433;databaseName=BeaconculinaryTest;encrypt=false;trustServerCertificate=true";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var url = environment.getProperty("TEST_DATASOURCE_URL", DEFAULT_URL);
        var database = databaseName(url);
        if (database == null || !database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("Refusing to run tests against database '" + database
                    + "': the tests delete data. Point TEST_DATASOURCE_URL at a database whose name contains 'test'.");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("testDatabase", Map.of("spring.datasource.url", url)));
    }

    private static String databaseName(String url) {
        for (var part : url.split(";")) {
            var pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("databaseName")) {
                return pair[1].trim();
            }
        }
        return null;
    }
}
