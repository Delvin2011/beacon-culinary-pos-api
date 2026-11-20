package com.beaconculinary.api.menu;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T0-5a step 4: the /admin/meal-periods endpoints, their rules and the history (contract section 3). */
@SpringBootTest
@AutoConfigureMockMvc
class MealPeriodAdminIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    private String adminToken;
    private Long componentId;

    @BeforeEach
    void setUp() throws Exception {
        MealPeriodFixture.clear(jdbc);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        MealPeriodFixture.clear(jdbc);
        if (componentId != null) {
            jdbc.update("DELETE FROM [daily_component_stock] WHERE [component_catalog_id] = ?", componentId);
            jdbc.update("DELETE FROM [component_catalog] WHERE [id] = ?", componentId);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions create(String body) throws Exception {
        return send(post("/admin/meal-periods").contentType(MediaType.APPLICATION_JSON).content(body), adminToken);
    }

    private ResultActions update(long id, String body) throws Exception {
        return send(put("/admin/meal-periods/" + id).contentType(MediaType.APPLICATION_JSON).content(body), adminToken);
    }

    private ResultActions action(long id, String action) throws Exception {
        return send(post("/admin/meal-periods/" + id + "/" + action), adminToken);
    }

    private long createSupper() throws Exception {
        var response = create("{\"name\":\"T05 Supper\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private JsonNode history(String query) throws Exception {
        var response = send(get("/admin/meal-periods/history" + query), adminToken)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response);
    }

    /** An extra planned under a period on a day (relative to the site's today). */
    private void planExtra(long periodId, int daysFromToday) throws Exception {
        if (componentId == null) {
            var response = send(post("/admin/component-catalog").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"T05 Gravy " + System.nanoTime() + "\",\"extraPrice\":5.00}"), adminToken)
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            componentId = MAPPER.readTree(response).get("id").asLong();
        }
        jdbc.update("""
                INSERT INTO [daily_component_stock] ([component_catalog_id], [meal_period_id], [option_date], [extra_price],
                    [buffer_quantity], [buffer_remaining], [status])
                VALUES (?, ?, ?, 5.00, 10, 0, 'PLANNED')""", componentId, periodId, LocalDate.now(clock).plusDays(daysFromToday));
    }

    // ---- access and listing --------------------------------------------------------------------------

    @Test
    void onlyAnAdminMayUseThem() throws Exception {
        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        send(get("/admin/meal-periods"), cashier).andExpect(status().isForbidden());
        send(post("/admin/meal-periods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"T05 Supper\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}"), cashier)
                .andExpect(status().isForbidden());
        send(put("/admin/meal-periods/2").contentType(MediaType.APPLICATION_JSON).content("{}"), cashier).andExpect(status().isForbidden());
        send(post("/admin/meal-periods/2/deactivate"), cashier).andExpect(status().isForbidden());
        send(post("/admin/meal-periods/2/activate"), cashier).andExpect(status().isForbidden());
        send(get("/admin/meal-periods/history"), cashier).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/meal-periods")).andExpect(status().is4xxClientError());
    }

    @Test
    void theAdminListHasEveryPeriodWithItsAdminFields() throws Exception {
        var supper = createSupper();
        action(supper, "deactivate").andExpect(status().isOk());
        send(get("/admin/meal-periods"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Breakfast", "Lunch", "All Day", "T05 Supper")))
                .andExpect(jsonPath("$[0].startTime").value("07:00:00"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].sortOrder").value(10))
                .andExpect(jsonPath("$[0].lastChange").isEmpty())
                .andExpect(jsonPath("$[2].allDay").value(true))
                .andExpect(jsonPath("$[2].accepts").value("LIGHT_MEALS"))
                .andExpect(jsonPath("$[3].active").value(false))
                .andExpect(jsonPath("$[3].lastChange.action").value("DEACTIVATE"))
                .andExpect(jsonPath("$[3].lastChange.byName").isNotEmpty());
    }

    // ---- create ----------------------------------------------------------------------------------------

    @Test
    void createMakesAnActiveSetMealsPeriodThatGoesLast() throws Exception {
        create("{\"name\":\" T05 Supper \",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("T05 Supper"))
                .andExpect(jsonPath("$.startTime").value("17:00:00"))
                .andExpect(jsonPath("$.endTime").value("19:00:00"))
                .andExpect(jsonPath("$.allDay").value(false))
                .andExpect(jsonPath("$.accepts").value("SET_MEALS"))
                .andExpect(jsonPath("$.plannable").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sortOrder").value(40))
                .andExpect(jsonPath("$.lastChange.action").value("CREATE"));
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", hasItem("T05 Supper")));
        var entry = history("").get("content").get(0);
        assertThat(entry.get("action").asText()).isEqualTo("CREATE");
        assertThat(entry.get("before").isNull()).isTrue();
        assertThat(entry.get("after").get("startTime").asText()).isEqualTo("17:00:00");
    }

    @Test
    void createRefusesBadFieldsAllAtOnceAndANonObjectBody() throws Exception {
        create("{\"name\":\"\",\"startTime\":\"5pm\",\"endTime\":\"19:00:30\",\"sortOrder\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Required."))
                .andExpect(jsonPath("$.startTime").value("Use HH:mm."))
                .andExpect(jsonPath("$.endTime").value("Seconds must be 00."))
                .andExpect(jsonPath("$.sortOrder").value("Must be between 0 and 9999."));
        create("{\"name\":\"T05 Night\",\"startTime\":\"22:00\",\"endTime\":\"02:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.endTime").value("Overnight periods are not supported; the end must be after the start."));
        create("[1]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.body").value("Must be a JSON object."));
        create("not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.body").value("Must be a JSON object."));
        assertThat(history("").get("totalElements").asLong()).isZero();
    }

    @Test
    void anActiveNameIsTakenIgnoringCaseButAnInactiveOneIsNot() throws Exception {
        create("{\"name\":\"lunch\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Another active period is already called Lunch."));
        var supper = createSupper();
        action(supper, "deactivate").andExpect(status().isOk());
        create("{\"name\":\"T05 Supper\",\"startTime\":\"19:00\",\"endTime\":\"20:00\"}").andExpect(status().isCreated());
    }

    @Test
    void periodsMayTouchButNotOverlap() throws Exception {
        create("{\"name\":\"T05 Brunch\",\"startTime\":\"10:00\",\"endTime\":\"12:00\"}").andExpect(status().isCreated());
        create("{\"name\":\"T05 Late lunch\",\"startTime\":\"14:00\",\"endTime\":\"15:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_OVERLAP"))
                .andExpect(jsonPath("$.error").value("Overlaps Lunch (12:00–14:30)."))
                .andExpect(jsonPath("$.details.periodId").value(2))
                .andExpect(jsonPath("$.details.name").value("Lunch"))
                .andExpect(jsonPath("$.details.startTime").value("12:00:00"))
                .andExpect(jsonPath("$.details.endTime").value("14:30:00"));
    }

    // ---- update ----------------------------------------------------------------------------------------

    @Test
    void updateChangesNameTimesAndSortOrderAndRecordsBeforeAndAfter() throws Exception {
        update(2, "{\"name\":\"Midday\",\"startTime\":\"11:30\",\"endTime\":\"14:30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Midday"))
                .andExpect(jsonPath("$.startTime").value("11:30:00"))
                .andExpect(jsonPath("$.sortOrder").value(20));
        update(2, "{\"name\":\"Midday\",\"startTime\":\"11:30\",\"endTime\":\"14:30\",\"sortOrder\":5}")
                .andExpect(jsonPath("$.sortOrder").value(5));
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", contains("Midday", "Breakfast", "All Day")));

        var entries = history("?periodId=2").get("content");
        assertThat(entries).hasSize(2);
        var first = entries.get(1);
        assertThat(first.get("action").asText()).isEqualTo("UPDATE");
        assertThat(first.get("before").get("name").asText()).isEqualTo("Lunch");
        assertThat(first.get("before").get("startTime").asText()).isEqualTo("12:00:00");
        assertThat(first.get("after").get("name").asText()).isEqualTo("Midday");
        assertThat(first.get("changedByName").asText()).isNotBlank();
    }

    @Test
    void anUnchangedUpdateRecordsNothing() throws Exception {
        update(2, "{\"name\":\"Lunch\",\"startTime\":\"12:00\",\"endTime\":\"14:30:00\",\"sortOrder\":20}").andExpect(status().isOk());
        assertThat(history("").get("totalElements").asLong()).isZero();
    }

    @Test
    void updateChecksTheNameAndOverlapAgainstOtherPeriodsOnly() throws Exception {
        update(1, "{\"name\":\"LUNCH\",\"startTime\":\"07:00\",\"endTime\":\"10:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Another active period is already called Lunch."));
        update(1, "{\"name\":\"Breakfast\",\"startTime\":\"07:00\",\"endTime\":\"12:30\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_OVERLAP"));
        update(1, "{\"name\":\"Breakfast\",\"startTime\":\"06:30\",\"endTime\":\"12:00\"}").andExpect(status().isOk());
        update(999999, "{\"name\":\"X\",\"startTime\":\"06:30\",\"endTime\":\"07:00\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEAL_PERIOD_NOT_FOUND"));
    }

    // ---- All Day ---------------------------------------------------------------------------------------

    @Test
    void allDayKeepsNoTimesAndCantBeDeactivatedButCanBeRenamed() throws Exception {
        update(3, "{\"name\":\"All Day\",\"startTime\":\"06:00\",\"endTime\":\"18:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALL_DAY_PROTECTED"));
        action(3, "deactivate")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALL_DAY_PROTECTED"));
        update(3, "{\"name\":\"Lunch\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Another active period is already called Lunch."));
        update(3, "{\"name\":\"Anytime\",\"startTime\":null,\"sortOrder\":99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Anytime"))
                .andExpect(jsonPath("$.startTime").isEmpty())
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.sortOrder").value(99));
    }

    // ---- deactivate and activate -----------------------------------------------------------------------

    @Test
    void deactivateIsRefusedWhileItemsAreDatedTodayOrLater() throws Exception {
        var supper = createSupper();
        planExtra(supper, -1);
        planExtra(supper, 2);
        planExtra(supper, 5);
        action(supper, "deactivate")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_IN_USE"))
                .andExpect(jsonPath("$.details.options").value(0))
                .andExpect(jsonPath("$.details.extras").value(2))
                .andExpect(jsonPath("$.details.fromDate").value(LocalDate.now(clock).plusDays(2).toString()));

        jdbc.update("DELETE FROM [daily_component_stock] WHERE [meal_period_id] = ? AND [option_date] >= ?", supper, LocalDate.now(clock));
        planExtra(supper, 0);
        action(supper, "deactivate").andExpect(status().isConflict()).andExpect(jsonPath("$.details.extras").value(1));

        jdbc.update("DELETE FROM [daily_component_stock] WHERE [meal_period_id] = ? AND [option_date] >= ?", supper, LocalDate.now(clock));
        action(supper, "deactivate").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/meal-periods")).andExpect(jsonPath("$[*].name", not(hasItem("T05 Supper"))));
    }

    @Test
    void deactivateAndActivateAreRecordedOnceAndRepeatsChangeNothing() throws Exception {
        var supper = createSupper();
        action(supper, "deactivate").andExpect(status().isOk());
        action(supper, "deactivate").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        action(supper, "activate").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        action(supper, "activate").andExpect(status().isOk());
        var entries = history("?periodId=" + supper).get("content");
        assertThat(entries).extracting(entry -> entry.get("action").asText()).containsExactly("ACTIVATE", "DEACTIVATE", "CREATE");
        assertThat(entries.get(1).get("before").get("active").asBoolean()).isTrue();
        assertThat(entries.get(1).get("after").get("active").asBoolean()).isFalse();
        action(999999, "activate").andExpect(status().isNotFound());
    }

    @Test
    void activateChecksTheNameAndOverlapAgain() throws Exception {
        var supper = createSupper();
        action(supper, "deactivate").andExpect(status().isOk());
        update(supper, "{\"name\":\"Lunch\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}").andExpect(status().isOk());
        action(supper, "activate")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Another active period is already called Lunch."));
        update(supper, "{\"name\":\"T05 Supper\",\"startTime\":\"14:00\",\"endTime\":\"19:00\"}").andExpect(status().isOk());
        action(supper, "activate")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_OVERLAP"))
                .andExpect(jsonPath("$.details.name").value("Lunch"));
    }

    // ---- history ---------------------------------------------------------------------------------------

    @Test
    void historyIsNewestFirstFilteredAndPaged() throws Exception {
        var supper = createSupper();
        update(supper, "{\"name\":\"T05 Dinner\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}").andExpect(status().isOk());
        update(1, "{\"name\":\"Breakfast\",\"startTime\":\"06:30\",\"endTime\":\"10:00\"}").andExpect(status().isOk());

        var all = history("?size=2");
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(all.get("totalPages").asInt()).isEqualTo(2);
        assertThat(all.get("content")).extracting(entry -> entry.get("periodId").asLong()).containsExactly(1L, supper);
        assertThat(history("?size=2&page=1").get("content")).hasSize(1);
        assertThat(history("?periodId=" + supper).get("totalElements").asLong()).isEqualTo(2);
        assertThat(history("?periodId=999999").get("content")).isEmpty();
        assertThat(history("?size=500").get("size").asInt()).isEqualTo(200);
    }
}
