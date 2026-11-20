package com.beaconculinary.api.menu;

import com.beaconculinary.api.offline.OfflineDtos.SnapshotPeriod;
import com.beaconculinary.api.offline.OfflineSnapshotService;
import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-5a step 3: an inactive period is left out of GET /meal-periods and the offline snapshot, takes
 * no new plan items, and loses to the active period of the same name; the public list follows
 * sort_order and keeps its shape. Periods are switched off directly in the database here; the
 * admin endpoints come in step 4.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MealPeriodActiveIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MealPeriodRepository mealPeriodRepository;
    @Autowired
    private OfflineSnapshotService offlineSnapshotService;

    private String adminToken;
    private long supperId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        supperId = MealPeriodFixture.insert(jdbc, "T05 Supper", "16:00", "17:00", false, 40);
    }

    @AfterEach
    void tearDown() {
        MealPeriodFixture.clear(jdbc);
    }

    @Test
    void thePublicListHasActivePeriodsOnlyInSortOrderWithTheSameShape() throws Exception {
        mockMvc.perform(get("/meal-periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Breakfast", "Lunch", "All Day")))
                .andExpect(jsonPath("$[0].startTime").value("07:00:00"))
                .andExpect(jsonPath("$[0].plannable").value(true))
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[0].sortOrder").doesNotExist());

        jdbc.update("UPDATE [meal_periods] SET [active] = 1 WHERE [id] = ?", supperId);
        jdbc.update("UPDATE [meal_periods] SET [sort_order] = 5 WHERE [id] = 2");
        mockMvc.perform(get("/meal-periods"))
                .andExpect(jsonPath("$[*].name", contains("Lunch", "Breakfast", "All Day", "T05 Supper")));
    }

    @Test
    void nothingNewIsPlannedUnderAnInactivePeriod() throws Exception {
        mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealPeriodId\":" + supperId + ",\"optionDate\":\"2026-12-01\",\"mealCatalogId\":999999,\"plannedPortions\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEAL_PERIOD_INACTIVE"))
                .andExpect(jsonPath("$.error").value("Meal period T05 Supper is inactive."));
        mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentCatalogId\":999999,\"mealPeriodId\":" + supperId + ",\"optionDate\":\"2026-12-01\",\"bufferQuantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEAL_PERIOD_INACTIVE"));
    }

    @Test
    void aNameFindsTheActivePeriodBeforeAnInactiveOne() {
        var inactiveLunch = MealPeriodFixture.insert(jdbc, "Lunch", "16:00", "17:00", false, 90);
        assertThat(mealPeriodRepository.findByName("lunch")).map(MealPeriod::getId).contains(2L);
        assertThat(mealPeriodRepository.findByName("T05 Supper")).map(MealPeriod::getId).contains(supperId);

        jdbc.update("UPDATE [meal_periods] SET [name] = 'Midday' WHERE [id] = 2");
        assertThat(mealPeriodRepository.findByName("Lunch")).map(MealPeriod::getId).contains(inactiveLunch);
    }

    @Test
    void theOfflineSnapshotLeavesOutInactivePeriods() {
        assertThat(offlineSnapshotService.build().periods()).extracting(SnapshotPeriod::name)
                .containsExactly("All Day", "Breakfast", "Lunch");
        jdbc.update("UPDATE [meal_periods] SET [active] = 1 WHERE [id] = ?", supperId);
        assertThat(offlineSnapshotService.build().periods()).extracting(SnapshotPeriod::name)
                .containsExactly("All Day", "Breakfast", "Lunch", "T05 Supper");
    }
}
