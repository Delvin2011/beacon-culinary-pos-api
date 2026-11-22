package com.beaconculinary.api.common;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * T0-7: with {@code app.flyway.include-baseline} (env APP_FLYWAY_INCLUDEBASELINE) true, Flyway also reads
 * {@code classpath:db/baseline}. An empty database then starts from the B116 baseline (no demo data), and a
 * database built from it validates; databases with full migration history ignore the baseline either way.
 *
 * <p>Off by default, so an empty dev or test database replays every migration with the demo data the tests use.
 * On in the prod profile, and wherever a database built from the baseline runs (for example a test customer on
 * dev, with Flyway enabled there). docs/2026-11-22-new-customer-database.md
 */
@Component
public class FlywayBaselineLocation implements FlywayConfigurationCustomizer {
    public static final String BASELINE = "classpath:db/baseline";

    private final boolean includeBaseline;

    public FlywayBaselineLocation(@Value("${app.flyway.include-baseline:false}") boolean includeBaseline) {
        this.includeBaseline = includeBaseline;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (!includeBaseline) {
            return;
        }
        var baseline = new Location(BASELINE);
        var locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        if (locations.stream().noneMatch(l -> l.getDescriptor().equals(baseline.getDescriptor()))) {
            locations.add(baseline);
            configuration.locations(locations.toArray(Location[]::new));
        }
    }
}
