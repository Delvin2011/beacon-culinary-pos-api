package com.beaconculinary.api.menu;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotPeriod;
import com.beaconculinary.api.offline.OfflineSnapshotService;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
import com.beaconculinary.api.support.ReportFixture;
import com.beaconculinary.api.support.TestLocale;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-5a step 6: what a change made through /admin/meal-periods does (contract sections 6 and 7).
 * A time change and a deactivation apply at once to GET /menu/today, ordering and the offline
 * snapshot; past sales keep their period in the reports, under its current name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class MealPeriodEffectsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPORT_RANGE = "from=2026-10-05&to=2026-10-06";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private OfflineSnapshotService offlineSnapshotService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;
    @Autowired private RecipeRepository recipeRepository;

    private String adminToken;
    private String cashierToken;
    private PosFlowHelper pos;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 15)); // inside Lunch (12:00-14:30)
        MealPeriodFixture.clear(jdbc);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        ReportFixture.clear(jdbc);
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
        MealPeriodFixture.clear(jdbc);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + adminToken));
    }

    private void setLunch(String start, String end) throws Exception {
        admin(put("/admin/meal-periods/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lunch\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}"))
                .andExpect(status().isOk());
    }

    /** A READY stew under Lunch today, and an open shift to sell it in. */
    private long stewOnSale() throws Exception {
        var beef = pos.createComponent("T05 Beef", "15.00");
        var stew = pos.createDailyOption(2, pos.createMealCatalog("T05 Stew", "50.00", beef), 10);
        pos.openShift(cashierToken, "500.00");
        return stew;
    }

    private ResultActions sellStew(long stew) throws Exception {
        return pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(stew));
    }

    private ResultActions menuToday() throws Exception {
        return mockMvc.perform(get("/menu/today").header("Authorization", "Bearer " + cashierToken));
    }

    private SnapshotPeriod snapshotPeriod(String name) {
        return offlineSnapshotService.build().periods().stream()
                .filter(period -> period.name().equals(name)).findFirst().orElse(null);
    }

    // ---- times -----------------------------------------------------------------------------------------

    @Test
    void aTimeChangeAppliesAtOnceToTheMenuOrderingAndTheSnapshot() throws Exception {
        var stew = stewOnSale();
        menuToday().andExpect(jsonPath("$.options[*].id", hasItem((int) stew)));
        sellStew(stew).andExpect(status().isCreated());

        setLunch("12:30", "14:30"); // 12:15 is now before Lunch

        menuToday().andExpect(jsonPath("$.options[*].id", not(hasItem((int) stew))));
        sellStew(stew).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Meal option's meal period is not currently active."));
        assertThat(snapshotPeriod("Lunch").startTime()).isEqualTo(LocalTime.of(12, 30));

        clock.setTime(LocalTime.of(12, 30)); // the new start is inclusive
        sellStew(stew).andExpect(status().isCreated());

        setLunch("12:00", "14:30");
        clock.setTime(LocalTime.of(12, 15));
        menuToday().andExpect(jsonPath("$.options[*].id", hasItem((int) stew)));
        sellStew(stew).andExpect(status().isCreated());
    }

    @Test
    void aPeriodSellingTodayCantBeDeactivated() throws Exception {
        stewOnSale();
        admin(post("/admin/meal-periods/2/deactivate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_IN_USE"))
                .andExpect(jsonPath("$.details.options").value(1))
                .andExpect(jsonPath("$.details.fromDate").value(LocalDate.now(clock).toString()));
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", hasItem("Lunch")));
    }

    // ---- deactivate and activate ---------------------------------------------------------------------

    @Test
    void aDeactivatedPeriodLeavesTheListsTheMenuAndTheSnapshotAtOnce() throws Exception {
        var response = admin(post("/admin/meal-periods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"T05 Elevenses\",\"startTime\":\"11:00\",\"endTime\":\"11:30\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", hasItem("T05 Elevenses")));
        assertThat(snapshotPeriod("T05 Elevenses")).isNotNull();

        admin(post("/admin/meal-periods/" + id + "/deactivate")).andExpect(status().isOk());

        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", not(hasItem("T05 Elevenses"))));
        assertThat(snapshotPeriod("T05 Elevenses")).isNull();
        mockMvc.perform(get("/menu/today").param("period", "T05 Elevenses").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options").isEmpty());
        admin(post("/admin/daily-options").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mealPeriodId\":" + id + ",\"optionDate\":\"" + LocalDate.now(clock).plusDays(1)
                        + "\",\"mealCatalogId\":999999,\"plannedPortions\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEAL_PERIOD_INACTIVE"));

        admin(post("/admin/meal-periods/" + id + "/activate")).andExpect(status().isOk());
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", hasItem("T05 Elevenses")));
        assertThat(snapshotPeriod("T05 Elevenses")).isNotNull();
    }

    @Test
    void allDayStaysOnTheMenuAndCantBeSwitchedOff() throws Exception {
        admin(post("/admin/meal-periods/3/deactivate")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALL_DAY_PROTECTED"));
        admin(put("/admin/meal-periods/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"All Day\",\"startTime\":\"06:00\",\"endTime\":\"18:00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALL_DAY_PROTECTED"));
        assertThat(snapshotPeriod("All Day").allDay()).isTrue();
        assertThat(snapshotPeriod("All Day").startTime()).isNull();
    }

    // ---- reports ---------------------------------------------------------------------------------------

    @Test
    void pastSalesKeepTheirPeriodInReports_underItsCurrentName() throws Exception {
        clock.setInstant(LocalDate.of(2026, 10, 6).atTime(13, 0).atZone(TestLocale.ZONE).toInstant());
        ReportFixture.clear(jdbc);
        var fixture = ReportFixture.seed(jdbc, AuthTestHelper.cashierAId(mockMvc), AuthTestHelper.cashierBId(mockMvc),
                AuthTestHelper.adminId(mockMvc));
        var report = "/admin/reports/meal-extras-sales?" + REPORT_RANGE + "&groupBy=MEAL_PERIOD";
        admin(get(report)).andExpect(jsonPath("$.rows[*].mealPeriod", contains("Breakfast", "Lunch")))
                .andExpect(jsonPath("$.rows[*].portionsRung", contains(10, 14)));

        // Lunch renamed and retimed after the sales; a new, inactive period added.
        setLunchNamed("Midday", "11:00", "14:30");
        var response = admin(post("/admin/meal-periods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"T05 Supper\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}"))
                .andReturn().getResponse().getContentAsString();
        var supper = MAPPER.readTree(response).get("id").asLong();
        admin(post("/admin/meal-periods/" + supper + "/deactivate")).andExpect(status().isOk());

        admin(get(report)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].mealPeriod", contains("Breakfast", "Midday")))
                .andExpect(jsonPath("$.rows[*].portionsRung", contains(10, 14)))
                .andExpect(jsonPath("$.rows[*].netPortions", contains(10, 5)));
        admin(get("/admin/reports/meal-extras-sales?" + REPORT_RANGE + "&mealPeriodId=" + fixture.lunchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].mealPeriod", not(hasItem("Breakfast"))));
        // The report's period filter still offers every period, inactive ones included.
        admin(get("/admin/reports"))
                .andExpect(jsonPath("$[4].params[4].options[*].label", hasItem("Midday")))
                .andExpect(jsonPath("$[4].params[4].options[*].label", hasItem("T05 Supper")));
    }

    private void setLunchNamed(String name, String start, String end) throws Exception {
        admin(put("/admin/meal-periods/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}"))
                .andExpect(status().isOk());
    }
}
