package com.beaconculinary.api.support.baseline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-7: a database built from the baseline (db/migration + db/baseline on an empty database) has exactly the
 * schema of one built through every migration (db/migration only): tables, columns (type, nullability,
 * identity, computed, default), keys, checks, foreign keys, indexes with filters, views and triggers. Names SQL
 * Server generated are compared by their deterministic names. On a difference it fails with both sides listed.
 *
 * <p>Both databases are throwaway ones, dropped afterwards. Skipped when the test login can't create databases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BaselineSchemaIntegrationTests {
    @Autowired
    private Environment environment;

    @Test
    void theBaselineSchemaEqualsEveryMigrationApplied() throws Exception {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");

        try (var full = ThrowawayDatabase.create(url, username, password, "Full");
             var baseline = ThrowawayDatabase.create(url, username, password, "Base")) {
            var fullResult = full.flyway("classpath:db/migration").migrate();
            var baselineResult = baseline.flyway("classpath:db/migration", "classpath:db/baseline").migrate();
            assertThat(baselineResult.targetSchemaVersion).isEqualTo(fullResult.targetSchemaVersion);
            assertThat(baselineResult.migrationsExecuted)
                    .as("an empty database runs the baseline and only the migrations after it")
                    .isLessThan(fullResult.migrationsExecuted);

            SchemaCatalog fullSchema;
            SchemaCatalog baselineSchema;
            try (var a = full.connect(); var b = baseline.connect()) {
                fullSchema = SchemaCatalog.read(a);
                baselineSchema = SchemaCatalog.read(b);
            }
            var expected = fullSchema.describe();
            var actual = baselineSchema.describe();
            var onlyFull = new TreeSet<>(expected);
            onlyFull.removeAll(actual);
            var onlyBaseline = new TreeSet<>(actual);
            onlyBaseline.removeAll(expected);
            var report = new ArrayList<String>();
            onlyFull.forEach(line -> report.add("- only after V1..V" + fullResult.targetSchemaVersion + ": " + line));
            onlyBaseline.forEach(line -> report.add("+ only from the baseline: " + line));
            assertThat(report).as("Schema differences (regenerate the baseline? see docs/2026-11-22-new-customer-database.md)")
                    .isEmpty();
            assertThat(expected).hasSizeGreaterThan(500);
        }
    }
}
