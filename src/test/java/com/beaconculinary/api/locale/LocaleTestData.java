package com.beaconculinary.api.locale;

import org.springframework.jdbc.core.JdbcTemplate;

/** Test-database cleanup shared by the T0-2 tests. */
final class LocaleTestData {
    private LocaleTestData() {
    }

    /** O2–O4 rows that reference orders or shifts. Their own tests clear them, but an aborted run
     * can leave some, and ReportFixture.clear would then fail on the foreign keys. */
    static void clearOfflineAndTillLeftovers(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM [sale_exceptions]");
        jdbc.update("DELETE FROM [offline_grants]");
        jdbc.execute("DISABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events; "
                + "DELETE FROM shift_cash_count_events; "
                + "ENABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events;");
    }
}
