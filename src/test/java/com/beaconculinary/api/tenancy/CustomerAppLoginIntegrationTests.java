package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.inventory.LocationChangeStore;
import com.beaconculinary.api.menu.MealPeriodChangeStore;
import com.beaconculinary.api.settings.SiteSettingsStore;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-4: in multi mode the app logs in to a customer database with the APP login (db_datareader, db_datawriter, GRANT
 * EXECUTE): every multi-mode test runs with it (MultiCustomerFixture). Here, as the app's own code: it is that login and
 * not an owner; the application locks (sp_getapplock: AppLock and the settings, meal period and location stores), writes
 * to a table and to an insert-only history table (with its trigger), and the reporting views all work. And it cannot
 * create, alter or drop a table or create a view; only the migration login can.
 */
class CustomerAppLoginIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppLock appLock;
    @Autowired
    private SiteSettingsStore settings;
    @Autowired
    private MealPeriodChangeStore mealPeriods;
    @Autowired
    private LocationChangeStore locations;

    private MultiCustomerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = MultiCustomerFixture.get();
        CustomerMvc.ensureAdmin(fixture.direct("alpha"));
    }

    @Test
    void theAppUsesTheAppLogin_whichLocksReadsWritesAndReadsTheViews() {
        var adminId = fixture.direct("alpha").queryForObject("SELECT id FROM users WHERE email = ?", Long.class, AuthTestHelper.ADMIN_EMAIL);
        var tx = new TransactionTemplate(transactionManager);
        CustomerContext.callAs(fixture.customer("alpha"), () -> tx.execute(status -> {
            assertThat(jdbc.queryForObject("SELECT SUSER_NAME()", String.class)).isEqualTo(fixture.appLogin());
            assertThat(jdbc.queryForObject("SELECT IS_ROLEMEMBER('db_owner')", Integer.class)).as("not an owner").isZero();

            settings.lockForWrite();
            mealPeriods.lockForWrite();
            locations.lockForWrite();
            appLock.lock("t1-4-app-login", 5000);
            var now = LocalDateTime.now();
            settings.upsert("t14.probe", "on", now, adminId, "T1-4 probe");
            settings.insertChange("t14.probe", "SET", null, true, "on", false, now, adminId, "T1-4 probe");
            assertThat(settings.findAll()).anyMatch(saved -> saved.key().equals("t14.probe"));
            assertThat(settings.history("t14.probe", 0, 10)).hasSize(1);
            assertThat(settings.delete("t14.probe")).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v_shift_facts", Integer.class)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v_adjustment_facts", Integer.class)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isPositive();
            status.setRollbackOnly();
            return null;
        }));
        assertThat(fixture.direct("alpha").queryForObject(
                "SELECT COUNT(*) FROM site_setting_changes WHERE setting_key = 't14.probe'", Integer.class)).as("rolled back").isZero();
    }

    @Test
    void theAppLogin_cannotCreateAlterOrDropATable() {
        var app = fixture.asApp("alpha");
        assertThatThrownBy(() -> app.execute("CREATE TABLE [t14_not_allowed] ([id] INT)")).hasMessageContaining("permission");
        assertThatThrownBy(() -> app.execute("ALTER TABLE [site_settings] ADD [t14_not_allowed] INT")).hasMessageContaining("permission");
        assertThatThrownBy(() -> app.execute("DROP TABLE [site_settings]")).hasMessageContaining("permission");
        assertThatThrownBy(() -> app.execute("CREATE VIEW [t14_not_allowed_view] AS SELECT 1 AS [x]")).hasMessageContaining("permission");

        var direct = fixture.direct("alpha");
        assertThat(direct.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = 't14_not_allowed'", Integer.class)).isZero();
        assertThat(direct.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = 'site_settings'", Integer.class)).isOne();
        assertThat(direct.queryForObject("SELECT COUNT(*) FROM sys.columns WHERE name = 't14_not_allowed'", Integer.class)).isZero();
    }
}
