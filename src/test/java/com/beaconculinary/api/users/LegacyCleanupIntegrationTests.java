package com.beaconculinary.api.users;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.auth.Jwt;
import com.beaconculinary.api.auth.JwtConfig;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-3 legacy clean-up: what V112 leaves in the database, the removed endpoints, the USER role, and
 * that a deactivated staff member's name still shows in order, shift and adjustment history.
 *
 * <p>V112's guard and the USER-row conversion are proven separately on a throwaway database (see the
 * T0-3 change report): a fixture USER row has to exist before V112 runs, which a test inside the
 * migrated application can't arrange. These tests check the end state on any database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class LegacyCleanupIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtConfig jwtConfig;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;

    private String admin;
    private final List<Long> createdStaff = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
        TestStaffCleanup.delete(jdbc, createdStaff);
        createdStaff.clear();
    }

    // ---- database: what V112 leaves behind ----

    @Test
    void v112_leavesNoUserRole_noDefault_andNoLegacyTables() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'USER'", Integer.class)).isZero();
        var check = jdbc.queryForObject(
                "SELECT definition FROM sys.check_constraints WHERE name = 'CK_users_role'", String.class);
        assertThat(check).contains("'CASHIER'").doesNotContain("'USER'");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.default_constraints WHERE name = 'DF_users_role'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name IN ('addresses', 'profiles')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = 'legacy_role_conversions'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '112' AND success = 1", Integer.class)).isOne();
    }

    @Test
    void theDatabaseRefusesTheUserRole_andAUserWithoutARole() {
        // Each insert fails, so nothing is left behind.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (name, role, active) VALUES ('T03 Refused', 'USER', 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (name, active) VALUES ('T03 Refused', 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE name = 'T03 Refused'", Integer.class)).isZero();
    }

    @Test
    void theRoleEnumHasNoUser() {
        assertThat(Arrays.stream(Role.values()).map(Enum::name)).doesNotContain("USER");
    }

    // ---- removed endpoints ----

    @Test
    void removedEndpoints_areGone_forAdmins_andNeedSignInLikeAnyUnknownPath() throws Exception {
        var adminId = AuthTestHelper.adminId(mockMvc);
        var json = "{\"name\":\"X\",\"email\":\"t03.signup@canteen.local\",\"password\":\"secret12\"}";
        List<MockHttpServletRequestBuilder> removed = List.of(
                get("/users"),
                get("/users/" + adminId),
                post("/users").contentType(MediaType.APPLICATION_JSON).content(json),
                put("/users/" + adminId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"),
                delete("/users/" + adminId),
                get("/admin/hello"),
                get("/"));

        for (var request : removed) {
            var signedIn = mockMvc.perform(request.header("Authorization", "Bearer " + admin)).andReturn().getResponse();
            assertThat(signedIn.getStatus()).as(signedIn.toString()).isIn(404, 405);
        }
        // The same requests without a token: the global authenticated() rule answers first.
        for (var request : List.of(get("/users"), get("/users/" + adminId), delete("/users/" + adminId), get("/admin/hello"), get("/"))) {
            assertThat(mockMvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        // Nothing was deleted, renamed or created.
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, adminId)).isNotEqualTo("Hacked");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = 't03.signup@canteen.local'", Integer.class)).isZero();
    }

    @Test
    void keptUserEndpoints_stillWork() throws Exception {
        mockMvc.perform(get("/users/cashiers")).andExpect(status().isOk());
        mockMvc.perform(get("/users/login-tiles?screen=KITCHEN")).andExpect(status().isOk());
        // Own-password change is still routed (wrong old password: 401, as before).
        var adminId = AuthTestHelper.adminId(mockMvc);
        mockMvc.perform(post("/users/" + adminId + "/change-password").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"not-the-password\",\"newPassword\":\"whatever1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenStillCarryingTheUserRole_isUnauthenticated_notAServerError() throws Exception {
        var claims = Jwts.claims()
                .subject(String.valueOf(AuthTestHelper.adminId(mockMvc)))
                .add("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .build();
        var token = new Jwt(claims, jwtConfig.getSecretKey()).toString();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/staff").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    // ---- deactivation keeps history ----

    @Test
    void deactivatedStaff_stillNamedInShiftOrderAndAdjustmentHistory() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window
        var leaverId = createStaff(Map.of("name", "T03 Leaver", "role", "CASHIER", "pin", "271828"));
        var leaver = AuthTestHelper.loginWithPin(mockMvc, leaverId, "271828");
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);

        var optionId = pos.createDailyOption(pos.periodId("lunch"),
                pos.createMealCatalog("T03 Meal", "50.00", pos.createComponent("T03 Beef", "15.00")), 10);
        var shiftId = pos.openShift(leaver, "100.00");
        var orderId = pos.placeCashOrder(leaver, "50.00", new LineReq(optionId));
        pos.markDone(orderId);
        pos.adjust(leaver, orderId, "WHOLE_ORDER"); // a refund the leaver requested, an admin authorized
        pos.closeShift(leaver, shiftId, "100.00", pos.authorizePin(leaver)).andExpect(status().isOk());

        // Deactivate through /admin/staff, as staff are managed now.
        mockMvc.perform(put("/admin/staff/" + leaverId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("name", "T03 Leaver", "role", "CASHIER", "active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/admin/shifts/" + shiftId + "/activity").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shift.owner.name").value("T03 Leaver"))
                .andExpect(jsonPath("$.shift.closedBy.name").value("T03 Leaver"))
                .andExpect(jsonPath("$.orders[0].rungBy.name").value("T03 Leaver"))
                .andExpect(jsonPath("$.adjustments[0].requestedBy.name").value("T03 Leaver"));
        mockMvc.perform(get("/admin/shifts?ownerId=" + leaverId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].owner.name").value("T03 Leaver"));
        mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashierName").value("T03 Leaver"));
        // The row itself is kept, inactive.
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, leaverId)).isFalse();
    }

    private long createStaff(Map<String, Object> body) throws Exception {
        var json = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(json).get("id").asLong();
        createdStaff.add(id);
        return id;
    }
}
