package com.beaconculinary.api.locale;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T0-2: the business date flips at midnight Johannesburg (22:00 UTC), not at UTC or machine
 * midnight. Recorded on the code before SiteLocale; it must pass unchanged after. Instants are
 * UTC literals so the test doesn't lean on the zone it guards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=2026-11-01")
class MidnightBoundaryIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MEAL = "T0-2 Midnight Burger";
    /** 23:59:59 on Wed 18 Nov 2026, Johannesburg. */
    private static final Instant BEFORE = Instant.parse("2026-11-18T21:59:59Z");
    /** 00:00:00 on Thu 19 Nov 2026, Johannesburg. */
    private static final Instant AFTER = Instant.parse("2026-11-18T22:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(BEFORE.minusSeconds(60));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        cleanUp();
    }

    private void cleanUp() {
        LocaleTestData.clearOfflineAndTillLeftovers(jdbc);
        ReportFixture.clear(jdbc);
        jdbc.update("DELETE FROM daily_meal_options WHERE meal_catalog_id IN (SELECT id FROM meal_catalog WHERE name = ?)", MEAL);
        jdbc.update("DELETE FROM meal_catalog WHERE name = ?", MEAL);
    }

    private JsonNode call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        var text = response.getContentAsString();
        return text.isEmpty() ? MAPPER.nullNode() : MAPPER.readTree(text);
    }

    private long burgerOptionToday() throws Exception {
        var menu = MAPPER.readTree(mockMvc.perform(get("/menu/today")).andReturn().getResponse().getContentAsString());
        for (var option : menu.get("options")) {
            if (option.get("name").asText().equals(MEAL)) {
                return option.get("id").asLong();
            }
        }
        throw new AssertionError(MEAL + " is not on the till menu");
    }

    private JsonNode orderBurger() throws Exception {
        var payment = Map.of("method", "CASH", "amount", "55.00", "amountTendered", "55.00");
        var line = Map.of("dailyMealOptionId", burgerOptionToday(), "quantity", 1, "extras", List.of());
        return call(cashierToken, post("/orders"), Map.of("payments", List.of(payment), "lines", List.of(line)));
    }

    private String firstCloseDayBlocker(String day) throws Exception {
        return call(adminToken, post("/admin/days/" + day + "/close"), null).get("blockers").get(0).get("code").asText();
    }

    @Test
    void orderDate_flipsAtJohannesburgMidnight() throws Exception {
        var burgers = CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers");
        call(adminToken, post("/admin/meal-catalog"),
                Map.of("name", MEAL, "description", "desc", "price", "55.00", "categoryId", burgers, "active", true));
        call(cashierToken, post("/shifts/open"), Map.of("openingFloat", "100.00"));

        clock.setInstant(BEFORE);
        var before = orderBurger();
        assertThat(before.get("orderDate").asText()).as(before.toString()).isEqualTo("2026-11-18");

        clock.setInstant(AFTER);
        var after = orderBurger();
        assertThat(after.get("orderDate").asText()).as(after.toString()).isEqualTo("2026-11-19");
    }

    @Test
    void tradingDay_flipsAtJohannesburgMidnight() throws Exception {
        clock.setInstant(BEFORE);
        assertThat(firstCloseDayBlocker("2026-11-19")).isEqualTo("DAY_IN_FUTURE");
        assertThat(firstCloseDayBlocker("2026-11-18")).isEqualTo("NO_SHIFTS_FOR_DAY");

        clock.setInstant(AFTER);
        assertThat(firstCloseDayBlocker("2026-11-19")).isEqualTo("NO_SHIFTS_FOR_DAY");
        assertThat(firstCloseDayBlocker("2026-11-20")).isEqualTo("DAY_IN_FUTURE");
    }
    // A shift's opened_at (like an order's created_at) is stamped by the database
    // (SYSUTCDATETIME()), not the Clock, so a shift can't be placed just before midnight
    // through the API; the SQL views decide a shift's trading day from it.
}
