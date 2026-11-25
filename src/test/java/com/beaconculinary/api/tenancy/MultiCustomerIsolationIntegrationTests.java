package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.admin.InvalidAuthorizationTokenException;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.auth.protection.LoginProtectionStore;
import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.dashboard.DashboardService;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.menu.AlwaysOnMenuService;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-2: in multi-customer mode, data written as one customer is never visible as the other, through every way the code
 * reaches the database (readiness analysis D.10): JPA, JdbcTemplate, a native query, AppLock, REQUIRES_NEW
 * transactions (LoginProtectionStore, AdminAuthorizationService), a REQUIRES_NEW TransactionTemplate
 * (AlwaysOnMenuService, the same mechanism as OfflineSyncService's per-sale template) and the dashboard's read-only
 * template. With no customer, the access fails with the specific error and nothing is written anywhere. Each test
 * checks the databases directly, around the application.
 */
@ExtendWith(OutputCaptureExtension.class)
class MultiCustomerIsolationIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private LoginProtectionStore loginProtectionStore;
    @Autowired
    private AdminAuthorizationService adminAuthorization;
    @Autowired
    private AlwaysOnMenuService alwaysOnMenu;
    @Autowired
    private DashboardService dashboard;
    @Autowired
    private TradingCalendar calendar;
    @Autowired
    private AppLock appLock;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private Environment environment;

    private MultiCustomerFixture fixture;
    private CurrentCustomer alpha;
    private CurrentCustomer bravo;
    private TransactionTemplate transaction;
    private String marker;

    @BeforeEach
    void setUp() {
        fixture = MultiCustomerFixture.get();
        alpha = fixture.customer("alpha");
        bravo = fixture.customer("bravo");
        transaction = new TransactionTemplate(transactionManager);
        marker = "t12-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jpa_writesOnlyToTheCurrentCustomer() {
        CustomerContext.runAs(alpha, () -> users.save(user(marker, Role.CASHIER)));
        assertThat(count("alpha", "users", "name", marker)).isEqualTo(1);
        assertThat(count("bravo", "users", "name", marker)).isZero();
        assertThat(as(alpha, () -> users.findAll().stream().anyMatch(u -> marker.equals(u.getName())))).isTrue();
        assertThat(as(bravo, () -> users.findAll().stream().anyMatch(u -> marker.equals(u.getName())))).isFalse();
    }

    @Test
    void jdbcTemplate_writesOnlyToTheCurrentCustomer() {
        CustomerContext.runAs(bravo, () -> jdbc.update("INSERT INTO users (name, role) VALUES (?, 'KITCHEN')", marker));
        assertThat(count("bravo", "users", "name", marker)).isEqualTo(1);
        assertThat(count("alpha", "users", "name", marker)).isZero();
        assertThat(as(alpha, () -> jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE name = ?", Integer.class, marker))).isZero();
    }

    @Test
    void aNativeQuery_readsTheCurrentCustomersDatabase() {
        fixture.direct("alpha").update("INSERT INTO users (name, role) VALUES (?, 'KITCHEN')", marker);
        Supplier<Integer> nativeCount = () -> transaction.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM users WHERE name = :name")
                .setParameter("name", marker).getSingleResult()).intValue());
        assertThat(as(alpha, nativeCount)).isEqualTo(1);
        assertThat(as(bravo, nativeCount)).isZero();
    }

    @Test
    void appLock_isTakenInTheCurrentCustomersDatabase() throws Exception {
        var resource = "t12-lock-" + marker;
        var executor = Executors.newFixedThreadPool(2);
        try {
            CustomerContext.runAs(alpha, () -> transaction.executeWithoutResult(status -> {
                appLock.lock(resource, 1000);
                try {
                    // Another of alpha's transactions can't take it; bravo's (another database) can.
                    var alphaAgain = executor.submit(() -> tryLock(alpha, resource));
                    var bravoToo = executor.submit(() -> tryLock(bravo, resource));
                    assertThat(alphaAgain.get(30, TimeUnit.SECONDS)).as("alpha's lock is held").isFalse();
                    assertThat(bravoToo.get(30, TimeUnit.SECONDS)).as("bravo has its own database").isTrue();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requiresNew_loginProtectionStore_keepsItsWriteInTheCurrentCustomer_evenWhenTheCallerRollsBack() {
        var row = new LoginProtectionStore.AttemptRow(LocalDateTime.now(), LoginMethod.PASSWORD, marker, null,
                "127.0.0.1", "127.0.0.1", null, "t1-2 test", LoginOutcome.BAD_CREDENTIAL, false, null, null, null, null);
        CustomerContext.runAs(alpha, () -> transaction.executeWithoutResult(status -> {
            loginProtectionStore.saveAttempt(row, null);
            status.setRollbackOnly();
        }));
        assertThat(count("alpha", "login_attempts", "identifier", marker)).as("kept by its own transaction").isEqualTo(1);
        assertThat(count("bravo", "login_attempts", "identifier", marker)).isZero();
    }

    @Test
    void requiresNew_adminAuthorization_consumesOnlyTheCurrentCustomersToken() {
        var alphaDb = fixture.direct("alpha");
        var adminId = alphaDb.queryForObject("INSERT INTO users (name, role) OUTPUT INSERTED.id VALUES (?, 'ADMIN')", Long.class, marker);
        alphaDb.update("INSERT INTO authorization_tokens (token, admin_id, expires_at) VALUES (?, ?, DATEADD(hour, 1, SYSDATETIME()))",
                marker, adminId);

        assertThatThrownBy(() -> CustomerContext.runAs(bravo, () -> adminAuthorization.consumeToken(marker)))
                .isInstanceOf(InvalidAuthorizationTokenException.class);
        assertThat(count("alpha", "authorization_tokens", "token", marker)).as("untouched by bravo").isEqualTo(1);

        var admin = CustomerContext.callAs(alpha, () -> transaction.execute(status -> {
            var consumed = adminAuthorization.consumeToken(marker);
            status.setRollbackOnly();
            return consumed;
        }));
        assertThat(admin.getId()).as("alpha's own admin").isEqualTo(adminId);
        assertThat(count("alpha", "authorization_tokens", "token", marker)).as("burned in its own transaction").isZero();
    }

    @Test
    void requiresNewTransactionTemplate_alwaysOnMenu_createsOnlyInTheCurrentCustomer() {
        var lightMeals = fixture.direct("alpha").queryForObject(
                "SELECT id FROM catalog_categories WHERE group_code = 'LIGHT_MEALS' AND parent_id IS NULL", Long.class);
        fixture.direct("alpha").update("INSERT INTO meal_catalog (name, price, category_id) VALUES (?, 12.50, ?)", marker, lightMeals);
        var day = LocalDate.of(2031, 1, 1).plusDays(Math.abs(marker.hashCode() % 3000));

        var created = CustomerContext.callAs(alpha, () -> transaction.execute(status -> alwaysOnMenu.ensureAlwaysOnOptions(day)));
        assertThat(created).isPositive();
        assertThat(count("alpha", "daily_meal_options", "name", marker)).isEqualTo(1);

        CustomerContext.runAs(bravo, () -> alwaysOnMenu.ensureAlwaysOnOptions(day));
        assertThat(count("bravo", "daily_meal_options", "name", marker)).isZero();
        assertThat(count("bravo", "meal_catalog", "name", marker)).isZero();
    }

    @Test
    void theDashboardsReadOnlyTemplate_readsTheCurrentCustomersDatabase() {
        var meal = marker + "-meal";
        var alphaDb = fixture.direct("alpha");
        var alphaAdmin = alphaDb.queryForObject("INSERT INTO users (name, role) OUTPUT INSERTED.id VALUES (?, 'ADMIN')", Long.class, marker);
        var setMeals = alphaDb.queryForObject(
                "SELECT id FROM catalog_categories WHERE group_code = 'SET_MEALS' AND parent_id IS NULL", Long.class);
        var mealId = alphaDb.queryForObject("INSERT INTO meal_catalog (name, price, category_id) OUTPUT INSERTED.id VALUES (?, 30.00, ?)",
                Long.class, meal, setMeals);
        alphaDb.update("INSERT INTO daily_meal_options (meal_catalog_id, option_date, name, price, planned_portions, portions_remaining, status) "
                + "VALUES (?, ?, ?, 30.00, 10, 10, 'PLANNED')", mealId, java.sql.Date.valueOf(as(alpha, calendar::today)), meal);
        var bravoAdmin = fixture.direct("bravo").queryForObject(
                "INSERT INTO users (name, role) OUTPUT INSERTED.id VALUES (?, 'ADMIN')", Long.class, marker);

        signIn(alphaAdmin);
        var alphaView = as(alpha, () -> dashboard.section("sell-through", Map.of()).data().toString());
        signIn(bravoAdmin);
        var bravoView = as(bravo, () -> dashboard.section("sell-through", Map.of()).data().toString());

        assertThat(alphaView).contains(meal);
        assertThat(bravoView).doesNotContain(meal);
    }

    @Test
    void noCustomer_isTheSpecificError_andNothingIsWrittenAnywhere(CapturedOutput output) {
        assertThatThrownBy(() -> users.save(user(marker, Role.CASHIER))).hasRootCauseInstanceOf(NoCurrentCustomerException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (name, role) VALUES (?, 'KITCHEN')", marker))
                .hasRootCauseInstanceOf(NoCurrentCustomerException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> loginProtectionStore.saveAttempt(
                new LoginProtectionStore.AttemptRow(LocalDateTime.now(), LoginMethod.PASSWORD, marker, null, "127.0.0.1",
                        "127.0.0.1", null, "t1-2 test", LoginOutcome.BAD_CREDENTIAL, false, null, null, null, null), null)))
                .hasRootCauseInstanceOf(NoCurrentCustomerException.class);
        for (var slug : List.of("alpha", "bravo")) {
            assertThat(count(slug, "users", "name", marker)).as(slug).isZero();
            assertThat(count(slug, "login_attempts", "identifier", marker)).as(slug).isZero();
        }
        assertThat(output.getAll().contains(environment.getRequiredProperty("spring.datasource.password")))
                .as("the database password appears in the output").isFalse();
    }

    @Test
    void aRequest_usesTheCustomerOfItsAddress_andTheBackendsOwnAddressServesNoCustomerData() throws Exception {
        assertThat(mvc.perform(get("/public/board/today").header("Host", "alpha.pos.localhost"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/public/board/today").header("Host", "bravo.pos.localhost"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        var platform = mvc.perform(get("/public/board/today").header("Host", "localhost")).andReturn().getResponse();
        assertThat(platform.getStatus()).isEqualTo(404);
        assertThat(platform.getContentAsString()).isEmpty();
    }

    // --- helpers ---

    private boolean tryLock(CurrentCustomer customer, String resource) {
        return CustomerContext.callAs(customer, () -> {
            try {
                transaction.executeWithoutResult(status -> appLock.lock(resource, 0));
                return true;
            } catch (AppLock.LockTimeoutException e) {
                return false;
            }
        });
    }

    private <T> T as(CurrentCustomer customer, Supplier<T> work) {
        return CustomerContext.callAs(customer, work);
    }

    private int count(String slug, String table, String column, String value) {
        return fixture.direct(slug).queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    private static User user(String name, Role role) {
        var user = new User();
        user.setName(name);
        user.setRole(role);
        user.setActive(true);
        return user;
    }

    private static void signIn(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

}
