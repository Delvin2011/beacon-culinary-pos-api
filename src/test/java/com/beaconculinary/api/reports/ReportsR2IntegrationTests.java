package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static com.beaconculinary.api.support.ReportFixture.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reports R2 acceptance: every figure in the work order's expected tables and variations, the
 * checks, the envelope additions (option objects, required, page), access and export. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ReportsR2IntegrationTests {
    private static final String RANGE = "from=2026-10-05&to=2026-10-06";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private ReportFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(TUESDAY.atTime(13, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        ReportFixture.clear(jdbc);
        fixture = ReportFixture.seed(jdbc, AuthTestHelper.cashierAId(mockMvc), AuthTestHelper.cashierBId(mockMvc),
                AuthTestHelper.adminId(mockMvc));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        ReportFixture.clear(jdbc);
    }

    private ResultActions run(String key, String query) throws Exception {
        return as(adminToken, "/admin/reports/" + key + "?" + query);
    }

    private ResultActions as(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    // --- Catalogue, envelope additions and access -------------------------------------------

    @Test
    void catalogue_listsAllSixForAdmin_withOptionObjectsAndRequired() throws Exception {
        as(adminToken, "/admin/reports").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", contains("cash-up-variance", "meal-extras-sales", "sales-summary",
                        "account-balances", "account-statement", "tender-summary")))
                // Meal & Extras: plain-string options, {value,label} meal periods, and a LIMIT.
                .andExpect(jsonPath("$[1].params[2].options", contains("ITEM", "MEAL_PERIOD")))
                .andExpect(jsonPath("$[1].params[4].name").value("mealPeriodId"))
                .andExpect(jsonPath("$[1].params[4].type").value("ID"))
                .andExpect(jsonPath("$[1].params[4].options[?(@.label == 'Lunch')].value", contains(Long.toString(fixture.lunchId))))
                .andExpect(jsonPath("$[1].params[4].required").doesNotExist())
                .andExpect(jsonPath("$[1].params[7].type").value("LIMIT"))
                .andExpect(jsonPath("$[1].params[7].options", contains("ALL", "10", "20", "50")))
                // Statement: a required account, A–Z.
                .andExpect(jsonPath("$[4].params[0].name").value("accountId"))
                .andExpect(jsonPath("$[4].params[0].required").value(true))
                .andExpect(jsonPath("$[4].params[0].options[*].label", contains("Acme Logistics", "Dormant Co", "Zulu Catering")));
    }

    @Test
    void inactiveAccount_isLabelledInTheOptions() throws Exception {
        jdbc.update("UPDATE accounts SET active = 0 WHERE id = ?", fixture.a3);
        as(adminToken, "/admin/reports").andExpect(jsonPath("$[4].params[0].options[1].label").value("Dormant Co (inactive)"));
    }

    @Test
    void headChef_runsMealExtrasSales_butNotTheAccountReports() throws Exception {
        var headChef = AuthTestHelper.loginAsHeadChef(mockMvc);
        as(headChef, "/admin/reports").andExpect(jsonPath("$[*].key", contains("meal-extras-sales", "sales-summary")));
        as(headChef, "/admin/reports/meal-extras-sales?" + RANGE).andExpect(status().isOk());
        as(headChef, "/admin/reports/account-balances?" + RANGE).andExpect(status().isForbidden());
        as(headChef, "/admin/reports/account-statement?" + RANGE + "&accountId=" + fixture.a1).andExpect(status().isForbidden());
    }

    @Test
    void otherRoles_get403() throws Exception {
        var tokens = List.of(AuthTestHelper.loginAsCashier(mockMvc), AuthTestHelper.loginAsStockAdmin(mockMvc),
                AuthTestHelper.loginAsStockClerk(mockMvc), AuthTestHelper.loginAsKitchen(mockMvc));
        for (var token : tokens) {
            for (var key : List.of("meal-extras-sales", "account-balances", "account-statement")) {
                as(token, "/admin/reports/" + key + "?" + RANGE).andExpect(status().isForbidden());
                as(token, "/admin/reports/" + key + "/export?format=csv&" + RANGE).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void statement_withoutOrWithUnknownAccount_is400() throws Exception {
        run("account-statement", RANGE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accountId").value("Select an account"));
        run("account-statement", RANGE + "&accountId=999999").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accountId").value("Select an account"));
        run("meal-extras-sales", RANGE + "&limit=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.limit").value("Show must be ALL or a positive whole number."));
        run("meal-extras-sales", RANGE + "&mealPeriodId=999999").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mealPeriodId").value("Select a meal period"));
    }

    // --- Meal & Extras Sales -------------------------------------------------------------------

    @Test
    void mealExtras_byItem_matchesFixture() throws Exception {
        run("meal-extras-sales", RANGE).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("SALES"))
                .andExpect(jsonPath("$.meta.params.groupBy").value("ITEM"))
                .andExpect(jsonPath("$.meta.params.limit").value("ALL"))
                .andExpect(jsonPath("$.page").value(nullValue()))
                .andExpect(jsonPath("$.columns[*].key", contains("rank", "item", "type", "plu", "mealPeriod", "daysOffered",
                        "portionsRung", "voided", "refundedOrRemoved", "netPortions", "avgNetPerDayOffered", "grossAsRung",
                        "shareOfGross", "complimentaryPortions", "complimentaryValue")))
                .andExpect(jsonPath("$.rows[*].rank", contains(1, 2, 3, 4, 5, 6, 7, 8)))
                .andExpect(jsonPath("$.rows[*].item", contains("Breakfast Plate", "Extra Egg", "Pap & Stew", "Chicken & Rice",
                        "Extra Chilli", "Extra Gravy", "Fish & Chips", "Side Salad")))
                .andExpect(jsonPath("$.rows[*].type", contains("Meal", "Extra", "Meal", "Meal", "Extra", "Extra", "Meal", "Extra")))
                .andExpect(jsonPath("$.rows[*].mealPeriod", contains("Breakfast", "Breakfast", "Lunch", "Lunch", "Lunch", "Lunch",
                        "Lunch", "Lunch")))
                .andExpect(jsonPath("$.rows[*].daysOffered", contains(2, 2, 2, 1, 2, 2, 1, 2)))
                .andExpect(jsonPath("$.rows[*].portionsRung", contains(6, 4, 6, 2, 0, 2, 0, 4)))
                .andExpect(jsonPath("$.rows[*].voided", contains(0, 0, 1, 0, 0, 1, 0, 1)))
                .andExpect(jsonPath("$.rows[*].refundedOrRemoved", contains(0, 0, 2, 0, 0, 1, 0, 3)))
                .andExpect(jsonPath("$.rows[*].netPortions", contains(6, 4, 3, 2, 0, 0, 0, 0)))
                .andExpect(jsonPath("$.rows[*].avgNetPerDayOffered", contains(3.00, 2.00, 1.50, 2.00, 0.00, 0.00, 0.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].grossAsRung", contains(240.00, 40.00, 210.00, 120.00, 0.00, 10.00, 0.00, 40.00)))
                .andExpect(jsonPath("$.rows[*].shareOfGross", contains(36.36, 6.06, 31.82, 18.18, 0.00, 1.52, 0.00, 6.06)))
                // No PLU column in the catalog yet: empty, not an error.
                .andExpect(jsonPath("$.rows[0].plu").value(nullValue()))
                .andExpect(jsonPath("$.totals.rank").value("Total"))
                .andExpect(jsonPath("$.totals.portionsRung").value(24))
                .andExpect(jsonPath("$.totals.voided").value(3))
                .andExpect(jsonPath("$.totals.refundedOrRemoved").value(6))
                .andExpect(jsonPath("$.totals.netPortions").value(15))
                .andExpect(jsonPath("$.totals.grossAsRung").value(660.00))
                .andExpect(jsonPath("$.totals.shareOfGross").value(100.00))
                // O10 (complimentary) is kept out of rung/gross: Pap & Stew 1 and Side Salad 1, R45 at list price.
                .andExpect(jsonPath("$.rows[*].complimentaryPortions", contains(0, 0, 1, 0, 0, 0, 0, 1)))
                .andExpect(jsonPath("$.rows[*].complimentaryValue", contains(0.00, 0.00, 35.00, 0.00, 0.00, 0.00, 0.00, 10.00)))
                .andExpect(jsonPath("$.totals.complimentaryPortions").value(2))
                .andExpect(jsonPath("$.totals.complimentaryValue").value(45.00))
                .andExpect(jsonPath("$.meta.definitionVersion").value(2))
                .andExpect(jsonPath("$.totals.daysOffered").doesNotExist())
                .andExpect(jsonPath("$.totals.avgNetPerDayOffered").doesNotExist())
                .andExpect(jsonPath("$.checks[0].key").value("gross-agrees-with-sales-summary"))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"))
                .andExpect(jsonPath("$.checks[0].actual").value(660.00));
    }

    @Test
    void mealExtras_byMealPeriod() throws Exception {
        run("meal-extras-sales", RANGE + "&groupBy=MEAL_PERIOD").andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].key", contains("mealPeriod", "itemsOffered", "portionsRung", "voided",
                        "refundedOrRemoved", "netPortions", "grossAsRung", "shareOfGross", "complimentaryPortions",
                        "complimentaryValue")))
                .andExpect(jsonPath("$.rows[*].mealPeriod", contains("Breakfast", "Lunch")))
                .andExpect(jsonPath("$.rows[*].itemsOffered", contains(2, 6)))
                .andExpect(jsonPath("$.rows[*].portionsRung", contains(10, 14)))
                .andExpect(jsonPath("$.rows[*].voided", contains(0, 3)))
                .andExpect(jsonPath("$.rows[*].refundedOrRemoved", contains(0, 6)))
                .andExpect(jsonPath("$.rows[*].netPortions", contains(10, 5)))
                .andExpect(jsonPath("$.rows[*].grossAsRung", contains(280.00, 380.00)))
                .andExpect(jsonPath("$.rows[*].shareOfGross", contains(42.42, 57.58)))
                .andExpect(jsonPath("$.rows[0].periodStart").doesNotExist())
                .andExpect(jsonPath("$.totals.mealPeriod").value("Total"))
                .andExpect(jsonPath("$.totals.itemsOffered").value(8))
                .andExpect(jsonPath("$.totals.portionsRung").value(24))
                .andExpect(jsonPath("$.totals.voided").value(3))
                .andExpect(jsonPath("$.totals.refundedOrRemoved").value(6))
                .andExpect(jsonPath("$.totals.netPortions").value(15))
                .andExpect(jsonPath("$.totals.grossAsRung").value(660.00))
                .andExpect(jsonPath("$.totals.shareOfGross").value(100.00))
                .andExpect(jsonPath("$.rows[*].complimentaryPortions", contains(0, 2)))
                .andExpect(jsonPath("$.totals.complimentaryValue").value(45.00));
    }

    @Test
    void mealExtras_mealsOnly_sharesOfTheFilteredGross_checkStillUnfiltered() throws Exception {
        run("meal-extras-sales", RANGE + "&type=MEAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].item", contains("Breakfast Plate", "Pap & Stew", "Chicken & Rice", "Fish & Chips")))
                .andExpect(jsonPath("$.rows[*].shareOfGross", contains(42.11, 36.84, 21.05, 0.00)))
                .andExpect(jsonPath("$.totals.grossAsRung").value(570.00))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"))
                .andExpect(jsonPath("$.checks[0].actual").value(660.00));
    }

    @Test
    void mealExtras_mealPeriodFilter() throws Exception {
        run("meal-extras-sales", RANGE + "&mealPeriodId=" + fixture.breakfastId).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.paramLabels.mealPeriodId").value("Breakfast"))
                .andExpect(jsonPath("$.rows[*].item", contains("Breakfast Plate", "Extra Egg")))
                .andExpect(jsonPath("$.totals.grossAsRung").value(280.00));
    }

    @Test
    void mealExtras_limit_showsTopRows_totalsCoverAll() throws Exception {
        run("meal-extras-sales", RANGE + "&limit=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].rank", contains(1, 2)))
                .andExpect(jsonPath("$.rows[*].item", contains("Breakfast Plate", "Extra Egg")))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalRows").value(8))
                .andExpect(jsonPath("$.totals.portionsRung").value(24))
                .andExpect(jsonPath("$.totals.grossAsRung").value(660.00));
    }

    @Test
    void mealExtras_ascending_zeroSellersFirstAToZ() throws Exception {
        run("meal-extras-sales", RANGE + "&direction=ASC").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].item", contains("Extra Chilli", "Extra Gravy", "Fish & Chips", "Side Salad",
                        "Chicken & Rice", "Pap & Stew", "Extra Egg", "Breakfast Plate")));
    }

    @Test
    void mealExtras_sortByGross() throws Exception {
        run("meal-extras-sales", RANGE + "&sortBy=GROSS_SALES").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].item", contains("Breakfast Plate", "Pap & Stew", "Chicken & Rice", "Extra Egg",
                        "Side Salad", "Extra Gravy", "Extra Chilli", "Fish & Chips")));
    }

    @Test
    void mealExtras_tamperedLine_failsGrossCheck() throws Exception {
        jdbc.update("UPDATE order_lines SET unit_price = 45.00 WHERE order_id = ?", fixture.o8);
        run("meal-extras-sales", RANGE).andExpect(jsonPath("$.checks[0].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[0].difference").value(5.00));
    }

    @Test
    void mealExtras_extrasRemovedThenOrderRefunded_countedOnce() throws Exception {
        jdbc.update("UPDATE orders SET status = 'REFUNDED', total = 0 WHERE id = ?", fixture.o9);
        fixture.adjustment(fixture.o9, fixture.s3, "WHOLE_ORDER", "REFUND", "35.00", "ACCOUNT_BALANCE",
                AuthTestHelper.adminId(mockMvc), AuthTestHelper.adminId(mockMvc), utc(TUESDAY, 12, 55));
        run("meal-extras-sales", RANGE).andExpect(status().isOk())
                // Pap & Stew: O9's portion moves from net to refunded.
                .andExpect(jsonPath("$.rows[?(@.item == 'Pap & Stew')].refundedOrRemoved", contains(3)))
                .andExpect(jsonPath("$.rows[?(@.item == 'Pap & Stew')].netPortions", contains(2)))
                // The removed extras stay in one bucket, counted once.
                .andExpect(jsonPath("$.rows[?(@.item == 'Extra Gravy')].portionsRung", contains(2)))
                .andExpect(jsonPath("$.rows[?(@.item == 'Extra Gravy')].refundedOrRemoved", contains(1)))
                .andExpect(jsonPath("$.rows[?(@.item == 'Side Salad')].refundedOrRemoved", contains(3)))
                .andExpect(jsonPath("$.totals.portionsRung").value(24))
                .andExpect(jsonPath("$.totals.refundedOrRemoved").value(7));
    }

    @Test
    void mealExtras_renamedMeal_keepsHistoryOnOneRow_andPriceChangeKeepsSnapshot() throws Exception {
        jdbc.update("UPDATE meal_catalog SET name = 'Pap & Beef Stew', price = 50.00 WHERE id = ?", fixture.catalog.get("Pap & Stew"));
        jdbc.update("UPDATE daily_meal_options SET name = 'Pap & Beef Stew', price = 50.00 WHERE meal_catalog_id = ?",
                fixture.catalog.get("Pap & Stew"));
        run("meal-extras-sales", RANGE).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].item", hasItem("Pap & Beef Stew")))
                .andExpect(jsonPath("$.rows[*].item", not(hasItem("Pap & Stew"))))
                .andExpect(jsonPath("$.rows[?(@.item == 'Pap & Beef Stew')].portionsRung", contains(6)))
                .andExpect(jsonPath("$.rows[?(@.item == 'Pap & Beef Stew')].grossAsRung", contains(210.00)))
                .andExpect(jsonPath("$.totals.grossAsRung").value(660.00));
    }

    @Test
    void mealExtras_emptyRange_emptyTable_zeroTotals_checksPass() throws Exception {
        run("meal-extras-sales", "from=2026-08-01&to=2026-08-02").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.totals.portionsRung").value(0))
                .andExpect(jsonPath("$.totals.grossAsRung").value(0.00))
                .andExpect(jsonPath("$.totals.shareOfGross").value(0.00))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
    }

    @Test
    void mealExtras_csvExport_matchesTheScreen() throws Exception {
        var bytes = as(adminToken, "/admin/reports/meal-extras-sales/export?format=csv&" + RANGE + "&limit=2")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var lines = new String(bytes, StandardCharsets.UTF_8).substring(1).split("\r\n");
        assertThat(lines).containsExactly(
                "Rank,Item,Type,PLU,Meal period,Days offered,Portions rung,Voided,Refunded/removed,Net portions,"
                        + "Avg net per day offered,Gross as rung,Share of gross,Complimentary portions,Complimentary value (list price)",
                "1,Breakfast Plate,Meal,,Breakfast,2,6,0,0,6,3.00,240.00,36.36,0,0.00",
                "2,Extra Egg,Extra,,Breakfast,2,4,0,0,4,2.00,40.00,6.06,0,0.00");
    }

    // --- Account Balances ----------------------------------------------------------------------

    @Test
    void accountBalances_matchFixture_andAllChecksPass() throws Exception {
        run("account-balances", RANGE).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("PAYMENTS_ACCOUNTS"))
                .andExpect(jsonPath("$.columns[*].key", contains("account", "openingBalance", "charges", "credits",
                        "paymentsReceived", "closingBalance", "accountOrders", "lastPayment")))
                .andExpect(jsonPath("$.rows[*].account", contains("Acme Logistics", "Zulu Catering", "Dormant Co")))
                .andExpect(jsonPath("$.rows[*].openingBalance", contains(150.00, 0.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].charges", contains(120.00, 50.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].credits", contains(0.00, 15.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].paymentsReceived", contains(100.00, 0.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].closingBalance", contains(170.00, 35.00, 0.00)))
                .andExpect(jsonPath("$.rows[*].accountOrders", contains(1, 1, 0)))
                .andExpect(jsonPath("$.rows[0].lastPayment").value("2026-10-06T12:00:00Z"))
                .andExpect(jsonPath("$.rows[1].lastPayment").value(nullValue()))
                .andExpect(jsonPath("$.rows[0].accountId").value(fixture.a1))
                .andExpect(jsonPath("$.totals.account").value("Total"))
                .andExpect(jsonPath("$.totals.openingBalance").value(150.00))
                .andExpect(jsonPath("$.totals.charges").value(170.00))
                .andExpect(jsonPath("$.totals.credits").value(15.00))
                .andExpect(jsonPath("$.totals.paymentsReceived").value(100.00))
                .andExpect(jsonPath("$.totals.closingBalance").value(205.00))
                .andExpect(jsonPath("$.totals.accountOrders").value(2))
                .andExpect(jsonPath("$.checks[*].key", contains("charges-agree-with-tender", "credits-agree-with-tender",
                        "balance-arithmetic", "matches-account-screen", "credit-balances")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS", "PASS", "PASS", "PASS")))
                .andExpect(jsonPath("$.checks[0].actual").value(170.00))
                .andExpect(jsonPath("$.checks[1].actual").value(15.00))
                .andExpect(jsonPath("$.checks[3].expected").value(205.00));
    }

    @Test
    void accountBalances_showFilters_andSortByName() throws Exception {
        run("account-balances", RANGE + "&show=OWING").andExpect(jsonPath("$.rows[*].account", contains("Acme Logistics", "Zulu Catering")))
                .andExpect(jsonPath("$.totals.closingBalance").value(205.00));
        run("account-balances", RANGE + "&show=MOVEMENT").andExpect(jsonPath("$.rows[*].account", contains("Acme Logistics", "Zulu Catering")));
        run("account-balances", RANGE + "&sortBy=NAME&direction=ASC")
                .andExpect(jsonPath("$.rows[*].account", contains("Acme Logistics", "Dormant Co", "Zulu Catering")));
    }

    @Test
    void accountBalances_rangeStartingMidHistory_carriesTheRightOpening() throws Exception {
        // From Sunday: Saturday's R200 charge is opening; Sunday's R50 payment is in the range.
        run("account-balances", "from=2026-09-27&to=2026-10-06")
                .andExpect(jsonPath("$.rows[0].account").value("Acme Logistics"))
                .andExpect(jsonPath("$.rows[0].openingBalance").value(200.00))
                .andExpect(jsonPath("$.rows[0].paymentsReceived").value(150.00))
                .andExpect(jsonPath("$.rows[0].closingBalance").value(170.00));
    }

    @Test
    void accountBalances_toInThePast_hasNoAccountScreenCheck() throws Exception {
        run("account-balances", "from=2026-10-05&to=2026-10-05")
                .andExpect(jsonPath("$.checks[*].key", not(hasItem("matches-account-screen"))))
                .andExpect(jsonPath("$.rows[0].closingBalance").value(270.00));
    }

    @Test
    void accountBalances_tamperedPaymentDate_failsAccountScreenCheck() throws Exception {
        // Moved into tomorrow: the screen counts it, the period to today doesn't.
        jdbc.update("UPDATE account_payments SET created_at = ? WHERE account_id = ? AND amount = 100.00",
                utc(TUESDAY.plusDays(1), 9, 0), fixture.a1);
        run("account-balances", RANGE)
                .andExpect(jsonPath("$.checks[?(@.key == 'matches-account-screen')].status", contains("FAIL")))
                .andExpect(jsonPath("$.checks[?(@.key == 'matches-account-screen')].difference", contains(100.00)));
    }

    @Test
    void accountBalances_unassignedAccountCharge_failsTenderCheck() throws Exception {
        jdbc.update("UPDATE order_payments SET account_id = NULL WHERE order_id = ?", fixture.o6);
        run("account-balances", RANGE)
                .andExpect(jsonPath("$.checks[?(@.key == 'charges-agree-with-tender')].status", contains("FAIL")));
    }

    @Test
    void accountInCredit_warns() throws Exception {
        fixture.settlement(fixture.a3, "40.00", "Prepaid", utc(TUESDAY, 9, 0));
        run("account-balances", RANGE)
                .andExpect(jsonPath("$.rows[2].account").value("Dormant Co"))
                .andExpect(jsonPath("$.rows[2].closingBalance").value(-40.00))
                .andExpect(jsonPath("$.checks[?(@.key == 'credit-balances')].status", contains("WARN")))
                .andExpect(jsonPath("$.checks[?(@.key == 'credit-balances')].detail", contains("1 account is in credit")));
    }

    // --- Account Statement ---------------------------------------------------------------------

    @Test
    void statement_acme() throws Exception {
        run("account-statement", RANGE + "&accountId=" + fixture.a1).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.paramLabels.accountId").value("Acme Logistics"))
                .andExpect(jsonPath("$.columns[*].key", contains("occurredAt", "type", "reference", "description", "charge",
                        "credit", "payment", "balance")))
                .andExpect(jsonPath("$.columns[1].statusTones.CHARGE").value("warning"))
                .andExpect(jsonPath("$.rows[*].type", contains("OPENING", "CHARGE", "PAYMENT")))
                .andExpect(jsonPath("$.rows[*].occurredAt", contains("2026-10-04T22:00:00Z", "2026-10-05T09:30:00Z",
                        "2026-10-06T12:00:00Z")))
                .andExpect(jsonPath("$.rows[*].reference", contains(null, "Order #6", "Payment #" + paymentId("100.00"))))
                .andExpect(jsonPath("$.rows[2].description").value("EFT"))
                .andExpect(jsonPath("$.rows[1].charge").value(120.00))
                .andExpect(jsonPath("$.rows[2].payment").value(100.00))
                .andExpect(jsonPath("$.rows[*].balance", contains(150.00, 270.00, 170.00)))
                .andExpect(jsonPath("$.totals.charge").value(120.00))
                .andExpect(jsonPath("$.totals.credit").value(0.00))
                .andExpect(jsonPath("$.totals.payment").value(100.00))
                .andExpect(jsonPath("$.totals.balance").value(170.00))
                .andExpect(jsonPath("$.checks[0].key").value("closing-agrees-with-balances-report"))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
    }

    @Test
    void statement_zulu() throws Exception {
        run("account-statement", RANGE + "&accountId=" + fixture.a2).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].type", contains("OPENING", "CHARGE", "CREDIT")))
                .andExpect(jsonPath("$.rows[*].reference", contains(null, "Order #2", "Order #2")))
                .andExpect(jsonPath("$.rows[2].description").value("Extras removed"))
                .andExpect(jsonPath("$.rows[2].occurredAt").value("2026-10-06T10:45:00Z"))
                .andExpect(jsonPath("$.rows[*].balance", contains(0.00, 50.00, 35.00)))
                .andExpect(jsonPath("$.totals.credit").value(15.00))
                .andExpect(jsonPath("$.totals.balance").value(35.00))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
    }

    @Test
    void statement_xlsxTitleBlockNamesTheAccount() throws Exception {
        var bytes = as(adminToken, "/admin/reports/account-statement/export?format=xlsx&" + RANGE + "&accountId=" + fixture.a1)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("Report");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Account Statement");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Parameters: accountId=Acme Logistics, from=2026-10-05, to=2026-10-06");
            // Header on row 5; opening, charge and payment rows; then the closing totals row.
            assertThat(sheet.getRow(8).getCell(7).getNumericCellValue()).isEqualTo(170.0);
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("Closing balance");
            assertThat(sheet.getRow(9).getCell(7).getNumericCellValue()).isEqualTo(170.0);
        }
    }

    private long paymentId(String amount) {
        return jdbc.queryForObject("SELECT id FROM account_payments WHERE account_id = ? AND amount = ?", Long.class,
                fixture.a1, new java.math.BigDecimal(amount));
    }
}
