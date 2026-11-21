package com.beaconculinary.api.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T0-5c: puts stock locations back as V115 left them, for tests that rename, add or reassign them.
 *
 * <p>Locations can't be deleted once they have history (location_changes is insert-only and
 * references them), so a location a test created is deactivated instead; inactive rows are
 * harmless (names are only unique among active locations). Kitchen is the seeded secondary: the
 * secondary with the lowest id. Run before and after such tests, once their stock movements,
 * requests and stock takes are gone.
 */
public final class LocationFixture {
    private LocationFixture() {
    }

    public static void reset(JdbcTemplate jdbc) {
        var kitchen = kitchenId(jdbc);
        jdbc.update("UPDATE [locations] SET [active] = 0 WHERE [kind] = 'SECONDARY' AND [id] <> ? AND [active] = 1", kitchen);
        jdbc.update("UPDATE [locations] SET [name] = 'Main Store', [sort_order] = 10 WHERE [kind] = 'PRIMARY'");
        jdbc.update("UPDATE [locations] SET [name] = 'Kitchen', [sort_order] = 20, [active] = 1 WHERE [id] = ?", kitchen);
        jdbc.update("UPDATE [location_assignments] SET [location_id] = ?, [updated_at] = NULL, [updated_by] = NULL, "
                + "[updated_by_name] = NULL", kitchen);
    }

    public static long primaryId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT [id] FROM [locations] WHERE [kind] = 'PRIMARY'", Long.class);
    }

    public static long kitchenId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT MIN([id]) FROM [locations] WHERE [kind] = 'SECONDARY'", Long.class);
    }
}
