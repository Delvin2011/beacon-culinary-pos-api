package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-3: a full offline sale in multi mode, with each customer's own till, cashier, shift, grant and snapshot. Synced at
 * alpha, it lands only in alpha's database; the same sale (same client order id) synced at bravo with bravo's grant is
 * bravo's own order, and alpha's order can't be read at bravo. A grant from alpha means nothing at bravo.
 */
class MultiCustomerOfflineSyncIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OfflineProperties offline;
    @Autowired
    private Clock clock;

    private MultiCustomerFixture fixture;
    private Side alpha;
    private Side bravo;

    /** One customer's side of the test: its address, database, till, cashier, shift, grant and a READY item. */
    private record Side(MockMvc mvc, JdbcTemplate db, O3Tills tills, O3Tills.Till till, O3Tills.Cashier cashier,
                        long shiftId, JsonNode grant, long optionId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = MultiCustomerFixture.get();
        offline.setEnabled(true);
        alpha = side("alpha");
        bravo = side("bravo");
    }

    @AfterEach
    void tearDown() {
        offline.setEnabled(false);
        // The tills, shifts and synced orders stay: the fixture databases are throwaway (dropped when the test JVM
        // ends), the orders reference the grants, and every other multi-mode test counts only its own rows.
    }

    @Test
    void aSaleSyncedAtAlpha_landsOnlyInAlpha_andTheSameSaleAtBravo_isBravosOwn() throws Exception {
        var clientOrderId = UUID.randomUUID().toString();

        var atAlpha = sync(alpha, List.of(sale(alpha, clientOrderId)));
        // Recorded (CREATED, or CREATED_WITH_EXCEPTIONS for anything the server flags about this hand-made sale).
        assertThat(atAlpha.get("result").asText()).startsWith("CREATED");
        var alphaOrderId = atAlpha.get("orderId").asLong();
        assertThat(orders(alpha.db(), clientOrderId)).isEqualTo(1);
        assertThat(orders(bravo.db(), clientOrderId)).as("nothing at bravo").isZero();

        var atBravo = sync(bravo, List.of(sale(bravo, clientOrderId)));
        assertThat(atBravo.get("result").asText()).as("bravo has never seen this sale").startsWith("CREATED");
        assertThat(orders(bravo.db(), clientOrderId)).isEqualTo(1);
        assertThat(orders(alpha.db(), clientOrderId)).as("alpha still has only its own").isEqualTo(1);
        assertThat(alpha.db().queryForObject("SELECT [id] FROM [orders] WHERE [client_order_id] = ?", Long.class, clientOrderId))
                .as("alpha's order is the one synced at alpha").isEqualTo(alphaOrderId);
    }

    @Test
    void alphasGrant_meansNothingAtBravo() throws Exception {
        var clientOrderId = UUID.randomUUID().toString();
        var result = sendSales(bravo, List.of(alpha.grant().get("grant").asText()), List.of(sale(alpha, clientOrderId)));
        var status = result.getResponse().getStatus();
        if (status == 200) {
            var first = body(result).get("results").get(0);
            assertThat(first.get("result").asText()).as("refused, not created").doesNotStartWith("CREATED");
        } else {
            assertThat(status).isBetween(400, 499);
        }
        assertThat(orders(bravo.db(), clientOrderId)).as("bravo recorded no order from alpha's grant").isZero();
        assertThat(orders(alpha.db(), clientOrderId)).as("nor did alpha: the sale was sent to bravo").isZero();
    }

    // --- helpers ---

    private Side side(String slug) throws Exception {
        var db = fixture.direct(slug);
        CustomerMvc.ensureAdmin(db);
        var mvc = CustomerMvc.at(context, slug);
        var tills = new O3Tills(mvc, db, "T13Off", AuthTestHelper.loginAsAdmin(mvc));
        var till = tills.registerTill();
        var cashier = tills.createCashier();
        var token = tills.pinLogin(cashier, till);
        var shiftId = tills.openShift(token, till);
        var snapshot = mvc.perform(on(get("/pos/offline/snapshot"), till).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(snapshot.getResponse().getStatus()).as("snapshot at " + slug).isEqualTo(200);
        var grant = mvc.perform(on(post("/pos/offline/grant"), till).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(grant.getResponse().getStatus()).as(grant.getResponse().getContentAsString()).isEqualTo(201);
        return new Side(mvc, db, tills, till, cashier, shiftId, body(grant), readyLightMeal(db, slug));
    }

    /** An always-on light meal, READY today in the All Day period (the baseline has the period and the group). */
    private long readyLightMeal(JdbcTemplate db, String slug) {
        var lightMeals = db.queryForObject("SELECT id FROM catalog_categories WHERE group_code = 'LIGHT_MEALS' AND parent_id IS NULL", Long.class);
        var allDay = db.queryForObject("SELECT TOP (1) id FROM meal_periods WHERE accepts = 'LIGHT_MEALS'", Long.class);
        var mealId = db.queryForObject("INSERT INTO meal_catalog (name, price, category_id) OUTPUT INSERTED.id VALUES (?, 50.00, ?)",
                Long.class, "T13Off " + slug + " stew", lightMeals);
        return db.queryForObject("INSERT INTO daily_meal_options (meal_catalog_id, meal_period_id, option_date, name, price, status) "
                + "OUTPUT INSERTED.id VALUES (?, ?, ?, ?, 50.00, 'READY')", Long.class, mealId, allDay,
                java.sql.Date.valueOf(LocalDate.now(clock)), "T13Off " + slug + " stew");
    }

    private Map<String, Object> sale(Side side, String clientOrderId) {
        var seq = 7;
        var today = LocalDate.now(clock);
        var sale = new HashMap<String, Object>();
        sale.put("clientOrderId", clientOrderId);
        sale.put("grantId", side.grant().get("grantId").asText());
        sale.put("offlineSeq", seq);
        sale.put("offlineReceiptNumber", side.till().code() + "-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("-%04d", seq));
        sale.put("collectionNumber", side.till().code() + "-" + seq);
        sale.put("soldAt", Instant.now(clock).minusSeconds(120).toString());
        sale.put("tillClockAtSendUtc", Instant.now(clock).toString());
        sale.put("shiftId", side.shiftId());
        sale.put("cashierId", side.cashier().id());
        sale.put("unlockedOffline", false);
        sale.put("snapshotVersion", "test");
        var line = new HashMap<String, Object>();
        line.put("dailyMealOptionId", side.optionId());
        line.put("name", "Stew");
        line.put("station", "KITCHEN");
        line.put("quantity", 1);
        line.put("unitPrice", new BigDecimal("50.00"));
        line.put("lineTotal", new BigDecimal("50.00"));
        line.put("extras", List.of());
        sale.put("lines", List.of(line));
        sale.put("subtotal", new BigDecimal("50.00"));
        sale.put("total", new BigDecimal("50.00"));
        sale.put("vatRate", new BigDecimal("0.1500"));
        sale.put("vatIncludedAmount", new BigDecimal("6.52"));
        sale.put("payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"),
                "amountTendered", new BigDecimal("50.00"), "changeDue", BigDecimal.ZERO)));
        return sale;
    }

    private JsonNode sync(Side side, List<Map<String, Object>> sales) throws Exception {
        var result = sendSales(side, List.of(side.grant().get("grant").asText()), sales);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("results").get(0);
    }

    private org.springframework.test.web.servlet.MvcResult sendSales(Side side, List<String> grants, List<Map<String, Object>> sales)
            throws Exception {
        return side.mvc().perform(on(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/pos/offline/sales"), side.till())
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("grants", grants, "sales", sales)))).andReturn();
    }

    private static int orders(JdbcTemplate db, String clientOrderId) {
        return db.queryForObject("SELECT COUNT(*) FROM orders WHERE client_order_id = ?", Integer.class, clientOrderId);
    }
}
