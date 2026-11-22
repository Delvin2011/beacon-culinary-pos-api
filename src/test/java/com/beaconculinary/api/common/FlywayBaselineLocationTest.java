package com.beaconculinary.api.common;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-7: app.flyway.include-baseline adds db/baseline to Flyway's locations, once, and only when on. Flyway keeps its locations in its own order, which doesn't matter. */
class FlywayBaselineLocationTest {

    private static String[] locations(boolean include, String... configured) {
        var configuration = Flyway.configure().locations(configured);
        new FlywayBaselineLocation(include).customize(configuration);
        return Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toArray(String[]::new);
    }

    @Test
    void offByDefault_theLocationsStayAsConfigured() {
        assertThat(locations(false, "classpath:db/migration")).containsExactly("classpath:db/migration");
    }

    @Test
    void on_addsTheBaseline() {
        assertThat(locations(true, "classpath:db/migration")).containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/baseline");
    }

    @Test
    void on_neverAddsItTwice() {
        assertThat(locations(true, "classpath:db/migration", "classpath:db/baseline"))
                .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/baseline");
    }
}
