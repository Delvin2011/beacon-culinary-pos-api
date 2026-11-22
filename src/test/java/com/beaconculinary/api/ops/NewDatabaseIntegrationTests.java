package com.beaconculinary.api.ops;

import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-7: a new customer database set up by {@link NewDatabaseCommand}: only the REQUIRED reference rows, no demo
 * data or legacy tables, the starter on request (and harmless twice), migrations after the baseline apply on top,
 * a database that isn't empty or has another collation is refused, and nothing about the connection is printed.
 * Each test uses its own throwaway database, created with the expected collation like a customer's. Skipped
 * when the test login can't create databases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NewDatabaseIntegrationTests {
    /** Dropped by V10, V16 and V112 (T0-3); a new database must never have them. */
    private static final List<String> LEGACY_TABLES = List.of("addresses", "profiles", "wishlist", "cart_items", "carts",
            "product_variants", "products", "categories", "order_item_modifiers", "order_items", "modifiers",
            "modifier_groups", "menu_item_variants", "menu_items", "menu_categories");
    /** Tables only demo seeds fill (V13, V27, V29, V58, V59 and the seed-user migrations). */
    private static final List<String> DEMO_TABLES = List.of("users", "meal_catalog", "meal_catalog_components",
            "component_catalog", "daily_meal_options", "daily_component_stock", "ingredients", "recipes", "recipe_lines",
            "grv", "grv_lines", "ingredient_stock_movements");

    @Autowired
    private Environment environment;

    private String url;
    private String username;
    private String password;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        url = environment.getRequiredProperty("spring.datasource.url");
        username = environment.getRequiredProperty("spring.datasource.username");
        password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
    }

    private int run(ThrowawayDatabase db, String... args) {
        out.reset();
        err.reset();
        return new NewDatabaseCommand(db.url(), db.username(), db.password(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.systemUTC()).run(args);
    }

    /** A throwaway database with the collation new customer databases have. */
    private ThrowawayDatabase customerDatabase(String label) throws SQLException {
        return ThrowawayDatabase.create(url, username, password, label, NewDatabaseCommand.DEFAULT_COLLATION);
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<String, Long> referenceCounts(Connection c) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        for (var table : List.of("locations", "location_assignments", "meal_periods", "catalog_categories", "component_categories",
                "count_sheet_categories", "receipt_settings")) {
            counts.put(table, count(c, "SELECT COUNT(*) FROM [" + table + "]"));
        }
        return counts;
    }

    @Test
    void aNewDatabaseHasOnlyTheRequiredReferenceData_andNoDemoDataOrLegacyTables() throws Exception {
        try (var db = customerDatabase("New")) {
            var started = System.nanoTime();
            assertThat(run(db)).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(NewDatabaseCommand.OK);
            var millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println("T0-7 timing: an empty database to the current schema in " + millis + " ms");
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("Schema at version").contains("No starter template");

            try (var c = db.connect()) {
                assertThat(count(c, "SELECT COUNT(*) FROM [flyway_schema_history] WHERE [type] = 'SQL_BASELINE'")).isEqualTo(1);
                for (var table : DEMO_TABLES) {
                    assertThat(count(c, "SELECT COUNT(*) FROM [" + table + "]")).as(table).isZero();
                }
                for (var table : LEGACY_TABLES) {
                    assertThat(count(c, "SELECT COUNT(*) FROM sys.tables WHERE [name] = '" + table + "'")).as(table).isZero();
                }
                assertThat(referenceCounts(c)).containsExactly(Map.entry("locations", 2L), Map.entry("location_assignments", 4L),
                        Map.entry("meal_periods", 1L), Map.entry("catalog_categories", 3L), Map.entry("component_categories", 0L),
                        Map.entry("count_sheet_categories", 0L), Map.entry("receipt_settings", 1L));
                assertThat(count(c, "SELECT COUNT(*) FROM [locations] WHERE [kind] = 'PRIMARY' AND [active] = 1")).isEqualTo(1);
                assertThat(count(c, "SELECT COUNT(*) FROM [location_assignments] a JOIN [locations] l ON l.[id] = a.[location_id] "
                        + "WHERE l.[kind] = 'SECONDARY' AND l.[active] = 1")).isEqualTo(4);
                assertThat(count(c, "SELECT COUNT(*) FROM [meal_periods] WHERE [all_day] = 1 AND [accepts] = 'LIGHT_MEALS' "
                        + "AND [active] = 1")).isEqualTo(1);
                assertThat(count(c, "SELECT COUNT(DISTINCT [group_code]) FROM [catalog_categories] WHERE [parent_id] IS NULL"))
                        .isEqualTo(3);
                assertThat(count(c, "SELECT COUNT(*) FROM [receipt_settings] WHERE [id] = 1")).isEqualTo(1);
                assertThat(count(c, "SELECT COUNT(*) FROM [site_settings]")).isZero();
            }
        }
    }

    @Test
    void theStarterIsOptional_andRunningItTwiceChangesNothing() throws Exception {
        try (var db = customerDatabase("Starter")) {
            assertThat(run(db, "--starter")).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(NewDatabaseCommand.OK);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("Starter template applied");
            try (var c = db.connect()) {
                var first = referenceCounts(c);
                assertThat(first).containsEntry("meal_periods", 3L).containsEntry("catalog_categories", 11L)
                        .containsEntry("component_categories", 7L).containsEntry("count_sheet_categories", 28L);
                NewDatabaseCommand.applyStarter(c);
                assertThat(referenceCounts(c)).isEqualTo(first);
                assertThat(count(c, "SELECT COUNT(*) FROM [meal_catalog]")).isZero();
                assertThat(count(c, "SELECT COUNT(*) FROM [users]")).isZero();
            }
        }
    }

    @Test
    void migrationsAfterTheBaselineApplyOnTop(@TempDir Path later) throws Exception {
        // A stand-in for the next real migration: it relies only on what both kinds of database have.
        Files.writeString(later.resolve("V117__t07_probe.sql"), """
                CREATE TABLE [t07_probe] ([id] INT NOT NULL PRIMARY KEY, [primary_location] NVARCHAR(50) NOT NULL);
                INSERT INTO [t07_probe] ([id], [primary_location]) SELECT 1, [name] FROM [locations] WHERE [kind] = 'PRIMARY';
                """);
        var laterLocation = "filesystem:" + later.toAbsolutePath();
        try (var fromBaseline = customerDatabase("OnTopBase");
             var fullPath = ThrowawayDatabase.create(url, username, password, "OnTopFull")) {
            assertThat(run(fromBaseline)).isEqualTo(NewDatabaseCommand.OK);
            var onBaseline = fromBaseline.flyway("classpath:db/migration", "classpath:db/baseline", laterLocation).migrate();
            assertThat(onBaseline.targetSchemaVersion).isEqualTo("117");
            assertThat(onBaseline.migrationsExecuted).isEqualTo(1);

            fullPath.flyway("classpath:db/migration").migrate();
            var onFull = fullPath.flyway("classpath:db/migration", "classpath:db/baseline", laterLocation).migrate();
            assertThat(onFull.targetSchemaVersion).isEqualTo("117");
            assertThat(onFull.migrationsExecuted).as("the baseline is ignored on a database with history").isEqualTo(1);
            for (var db : List.of(fromBaseline, fullPath)) {
                try (var c = db.connect()) {
                    assertThat(count(c, "SELECT COUNT(*) FROM [t07_probe]")).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void aDatabaseThatIsntEmptyIsRefused_andNothingAboutTheConnectionIsPrinted() throws Exception {
        try (var db = customerDatabase("Refuse")) {
            assertThat(run(db)).isEqualTo(NewDatabaseCommand.OK);
            var firstOutput = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
            assertThat(run(db)).isEqualTo(NewDatabaseCommand.REFUSED);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("isn't empty");
            assertThat(run(db, "--bogus")).isEqualTo(NewDatabaseCommand.USAGE);
            for (var output : List.of(firstOutput, err.toString(StandardCharsets.UTF_8))) {
                assertThat(output).doesNotContain("jdbc:").doesNotContain(db.name());
                // A boolean, so a failure can't print the password in the assertion message.
                assertThat(output.contains(password)).as("the output contains the database password").isFalse();
            }
        }
    }

    @Test
    void aDatabaseWithAnotherCollationIsRefused_unlessThatCollationIsExpected() throws Exception {
        try (var db = ThrowawayDatabase.create(url, username, password, "Collation", "Latin1_General_CI_AS")) {
            assertThat(run(db)).isEqualTo(NewDatabaseCommand.REFUSED);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("collation is Latin1_General_CI_AS")
                    .contains("COLLATE " + NewDatabaseCommand.DEFAULT_COLLATION);
            try (var c = db.connect()) {
                assertThat(count(c, "SELECT COUNT(*) FROM sys.tables")).as("nothing was created").isZero();
            }
            assertThat(run(db, "--collation", "Latin1_General_CI_AS")).as(err.toString(StandardCharsets.UTF_8))
                    .isEqualTo(NewDatabaseCommand.OK);
        }
    }
}
