package com.beaconculinary.api.tenancy.crosscustomer;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-10: end to end, as alpha, while bravo is busy at the same time (its own thread): a day's trading, an offline sync,
 * a suspension and a failed database update. Each customer sees and gets only its own; trouble at alpha never reaches
 * bravo. Area-by-area tests: CrossCustomerAreasIntegrationTests. docs/2026-11-27-cross-customer-tests.md
 */
class CrossCustomerScenariosIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OfflineProperties offline;
    @Autowired
    private CustomerDirectory directory;
    @Autowired
    private Clock clock;

    private MultiCustomerFixture fixture;
    private Side alpha;
    private Side bravo;
    private String marker;
    private ExecutorService bravosThread;

    private record Side(String slug, MockMvc mvc, JdbcTemplate db, String adminToken) {
    }

    private record Trader(Side side, O3Tills tills, O3Tills.Till till, O3Tills.Cashier cashier, String token, long shiftId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = MultiCustomerFixture.get();
        alpha = side("alpha");
        bravo = side("bravo");
        marker = "T110s " + Integer.toString(ThreadLocalRandom.current().nextInt(1 << 30), 36);
        bravosThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        bravosThread.shutdownNow();
        bravosThread.awaitTermination(30, TimeUnit.SECONDS);
        offline.setEnabled(false);
        fixture.platform().update("UPDATE [tenants] SET [status] = 'ACTIVE' WHERE [slug] IN ('alpha', 'bravo')");
        directory.refresh();
    }

    /** 1. Sign-in, shift, sales, receipts, the kitchen stream and the shift's reading at alpha, while bravo trades. */
    @Test
    void aDaysTradingAtAlpha_whileBravoTrades_eachSeesOnlyItsOwn() throws Exception {
        var a = trader(alpha);
        var b = trader(bravo);
        var alphaItem = marker + " alpha stew";
        var bravoItem = marker + " bravo curry";
        var alphaOption = readyOption(alpha, alphaItem, "50.00");
        var bravoOption = readyOption(bravo, bravoItem, "70.00");
        var alphaKitchen = openKitchenStream(alpha);
        var bravoKitchen = openKitchenStream(bravo);
        try {
            var bravoSales = whileBravo(() -> sales(b, bravoOption, "70.00", 5));
            var alphaSales = sales(a, alphaOption, "50.00", 3);
            var bravoStatuses = bravoSales.get(60, TimeUnit.SECONDS);

            assertThat(alphaSales).extracting(result -> result.getResponse().getStatus()).containsOnly(201);
            assertThat(bravoStatuses).extracting(result -> result.getResponse().getStatus()).containsOnly(201);
            for (var sale : alphaSales) {
                assertThat(sale.getResponse().getHeader("Location")).as("built from the request: alpha's own address")
                        .startsWith("http://alpha.pos.localhost/orders/");
                var receipt = body(admin(alpha, get("/orders/" + body(sale).get("id").asLong())));
                assertThat(receipt.get("receiptNumber").asText()).isNotBlank();
                assertThat(receipt.get("cashierName").asText()).isEqualTo(a.cashier().name());
            }
            assertThat(orders(alpha, a.shiftId())).isEqualTo(3);
            assertThat(orders(bravo, b.shiftId())).isEqualTo(5);

            assertThat(admin(alpha, get("/orders/today")).getResponse().getContentAsString())
                    .contains(alphaItem).doesNotContain(bravoItem);
            assertThat(admin(bravo, get("/orders/today")).getResponse().getContentAsString())
                    .contains(bravoItem).doesNotContain(alphaItem);
            assertThat(alphaKitchen.getResponse().getContentAsString()).contains(alphaItem).doesNotContain(bravoItem);
            assertThat(bravoKitchen.getResponse().getContentAsString()).contains(bravoItem).doesNotContain(alphaItem);

            assertThat(grossSales(alpha, a.shiftId())).as("alpha's shift reading").isEqualByComparingTo("150.00");
            assertThat(grossSales(bravo, b.shiftId())).as("bravo's shift reading").isEqualByComparingTo("350.00");
        } finally {
            alphaKitchen.getRequest().getAsyncContext().complete();
            bravoKitchen.getRequest().getAsyncContext().complete();
        }
    }

    /** 2. An offline sale synced at alpha while bravo syncs its own, with the same client order id. */
    @Test
    void anOfflineSaleSyncedAtAlpha_whileBravoSyncs_eachLandsOnlyInItsOwnDatabase() throws Exception {
        offline.setEnabled(true);
        var a = trader(alpha);
        var b = trader(bravo);
        var alphaGrant = grant(a);
        var bravoGrant = grant(b);
        var alphaOption = readyOption(alpha, marker + " wrap", "50.00");
        var bravoOption = readyOption(bravo, marker + " wrap", "50.00");
        var clientOrderId = UUID.randomUUID().toString();

        var start = new CountDownLatch(1);
        var atBravo = whileBravo(() -> {
            start.await();
            return sync(b, bravoGrant, offlineSale(b, bravoGrant, bravoOption, clientOrderId));
        });
        start.countDown();
        var atAlpha = sync(a, alphaGrant, offlineSale(a, alphaGrant, alphaOption, clientOrderId));

        assertThat(atAlpha.get("result").asText()).startsWith("CREATED");
        assertThat(atBravo.get(60, TimeUnit.SECONDS).get("result").asText()).startsWith("CREATED");
        assertThat(alpha.db().queryForObject("SELECT [shift_id] FROM [orders] WHERE [client_order_id] = ?", Long.class, clientOrderId))
                .as("alpha's sale, on alpha's shift").isEqualTo(a.shiftId());
        assertThat(bravo.db().queryForObject("SELECT [shift_id] FROM [orders] WHERE [client_order_id] = ?", Long.class, clientOrderId))
                .as("bravo's sale, on bravo's shift").isEqualTo(b.shiftId());
        assertThat(alpha.db().queryForObject("SELECT COUNT(*) FROM [orders] WHERE [client_order_id] = ?", Integer.class, clientOrderId))
                .isEqualTo(1);
        assertThat(bravo.db().queryForObject("SELECT COUNT(*) FROM [orders] WHERE [client_order_id] = ?", Integer.class, clientOrderId))
                .isEqualTo(1);
    }

    /** 3. Alpha suspended mid-day: refused everywhere, bravo trades on; reactivated, alpha trades again. */
    @Test
    void alphaSuspendedMidDay_isRefusedEverywhere_bravoUnaffected_andAlphaRecovers() throws Exception {
        refusedWhileAlphaIs("SUSPENDED", "CUSTOMER_SUSPENDED");
    }

    /**
     * 4. A database update that fails at alpha. MigrateCustomersCommand marks a customer whose update failed
     * MIGRATION_FAILED and leaves the others (MigrateCustomersCommandIntegrationTests); here that state, while bravo
     * trades: alpha is unavailable, bravo served; once alpha is ACTIVE again (a retry that worked), it trades again.
     */
    @Test
    void aFailedDatabaseUpdateAtAlpha_makesAlphaUnavailable_whileBravoIsServed() throws Exception {
        refusedWhileAlphaIs("MIGRATION_FAILED", "CUSTOMER_UNAVAILABLE");
    }

    private void refusedWhileAlphaIs(String status, String code) throws Exception {
        var a = trader(alpha);
        var b = trader(bravo);
        var alphaOption = readyOption(alpha, marker + " pie", "30.00");
        var bravoOption = readyOption(bravo, marker + " pie", "30.00");
        assertThat(sell(a, alphaOption, "30.00").getResponse().getStatus()).isEqualTo(201);

        var bravoSales = whileBravo(() -> sales(b, bravoOption, "30.00", 6));
        fixture.platform().update("UPDATE [tenants] SET [status] = ? WHERE [slug] = 'alpha'", status);
        directory.refresh();

        var refused = new ArrayList<MvcResult>();
        refused.add(alpha.mvc().perform(get("/health")).andReturn());
        refused.add(alpha.mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + AuthTestHelper.ADMIN_EMAIL + "\",\"password\":\"654321\"}")).andReturn());
        refused.add(sell(a, alphaOption, "30.00"));
        refused.add(admin(alpha, get("/orders/today")));
        refused.add(alpha.mvc().perform(get("/public/board/today")).andReturn());
        refused.add(alpha.mvc().perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + alpha.adminToken())
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn());
        for (var result : refused) {
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(503);
            assertThat(body(result).get("code").asText()).as(result.getRequest().getRequestURI()).isEqualTo(code);
        }
        assertThat(bravoSales.get(60, TimeUnit.SECONDS)).as("bravo's sales meanwhile")
                .extracting(result -> result.getResponse().getStatus()).containsOnly(201);
        assertThat(orders(bravo, b.shiftId())).isEqualTo(6);
        assertThat(orders(alpha, a.shiftId())).as("nothing written at alpha while refused").isEqualTo(1);

        fixture.platform().update("UPDATE [tenants] SET [status] = 'ACTIVE' WHERE [slug] = 'alpha'");
        directory.refresh();
        assertThat(alpha.mvc().perform(get("/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(sell(a, alphaOption, "30.00").getResponse().getStatus()).as("alpha trades again, same sign-in").isEqualTo(201);
        assertThat(orders(alpha, a.shiftId())).isEqualTo(2);
    }

    // --- helpers ---

    private <T> Future<T> whileBravo(Callable<T> work) {
        return bravosThread.submit(work);
    }

    private Side side(String slug) throws Exception {
        var db = fixture.direct(slug);
        CustomerMvc.ensureAdmin(db);
        var mvc = CustomerMvc.at(context, slug);
        return new Side(slug, mvc, db, AuthTestHelper.loginAsAdmin(mvc));
    }

    private Trader trader(Side side) throws Exception {
        var tills = new O3Tills(side.mvc(), side.db(), marker + " ", side.adminToken());
        var till = tills.registerTill();
        var cashier = tills.createCashier();
        var token = tills.pinLogin(cashier, till);
        return new Trader(side, tills, till, cashier, token, tills.openShift(token, till));
    }

    private static MvcResult admin(Side side, MockHttpServletRequestBuilder request) throws Exception {
        return side.mvc().perform(request.header("Authorization", "Bearer " + side.adminToken())).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static int orders(Side side, long shiftId) {
        return side.db().queryForObject("SELECT COUNT(*) FROM [orders] WHERE [shift_id] = ?", Integer.class, shiftId);
    }

    /** An always-on light meal, READY today in the All Day period (the baseline has the period and the group). */
    private long readyOption(Side side, String name, String price) {
        var lightMeals = side.db().queryForObject(
                "SELECT [id] FROM [catalog_categories] WHERE [group_code] = 'LIGHT_MEALS' AND [parent_id] IS NULL", Long.class);
        var allDay = side.db().queryForObject("SELECT TOP (1) [id] FROM [meal_periods] WHERE [accepts] = 'LIGHT_MEALS'", Long.class);
        var mealId = side.db().queryForObject("INSERT INTO [meal_catalog] ([name], [price], [category_id]) OUTPUT INSERTED.[id] "
                + "VALUES (?, ?, ?)", Long.class, name, new BigDecimal(price), lightMeals);
        return side.db().queryForObject("INSERT INTO [daily_meal_options] ([meal_catalog_id], [meal_period_id], [option_date], "
                        + "[name], [price], [status]) OUTPUT INSERTED.[id] VALUES (?, ?, ?, ?, ?, 'READY')", Long.class,
                mealId, allDay, java.sql.Date.valueOf(LocalDate.now(clock)), name, new BigDecimal(price));
    }

    private static MvcResult sell(Trader trader, long optionId, String amount) throws Exception {
        var order = Map.of("clientOrderId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal(amount), "amountTendered", new BigDecimal(amount))));
        return trader.side().mvc().perform(on(post("/orders"), trader.till()).header("Authorization", "Bearer " + trader.token())
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(order))).andReturn();
    }

    private static List<MvcResult> sales(Trader trader, long optionId, String amount, int count) throws Exception {
        var results = new ArrayList<MvcResult>();
        for (int i = 0; i < count; i++) {
            results.add(sell(trader, optionId, amount));
        }
        return results;
    }

    private static MvcResult openKitchenStream(Side side) throws Exception {
        var stream = admin(side, get("/kitchen/orders/stream").accept(MediaType.TEXT_EVENT_STREAM));
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        return stream;
    }

    /** The shift reading's gross sales (the takings section). */
    private static BigDecimal grossSales(Side side, long shiftId) throws Exception {
        var reading = body(admin(side, get("/shifts/" + shiftId + "/reading")));
        for (var section : reading.get("sections")) {
            if ("takings".equals(section.path("key").asText())) {
                for (var item : section.get("items")) {
                    if ("Gross sales".equals(item.path("label").asText())) {
                        return item.get("value").decimalValue();
                    }
                }
            }
        }
        throw new AssertionError("no gross sales in the reading at " + side.slug());
    }

    private static JsonNode grant(Trader trader) throws Exception {
        var snapshot = trader.side().mvc().perform(on(get("/pos/offline/snapshot"), trader.till())
                .header("Authorization", "Bearer " + trader.token())).andReturn();
        assertThat(snapshot.getResponse().getStatus()).isEqualTo(200);
        var grant = trader.side().mvc().perform(on(post("/pos/offline/grant"), trader.till())
                .header("Authorization", "Bearer " + trader.token())).andReturn();
        assertThat(grant.getResponse().getStatus()).as(grant.getResponse().getContentAsString()).isEqualTo(201);
        return body(grant);
    }

    private Map<String, Object> offlineSale(Trader trader, JsonNode grant, long optionId, String clientOrderId) {
        var seq = 3;
        var today = LocalDate.now(clock);
        var sale = new HashMap<String, Object>();
        sale.put("clientOrderId", clientOrderId);
        sale.put("grantId", grant.get("grantId").asText());
        sale.put("offlineSeq", seq);
        sale.put("offlineReceiptNumber", trader.till().code() + "-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("-%04d", seq));
        sale.put("collectionNumber", trader.till().code() + "-" + seq);
        sale.put("soldAt", Instant.now(clock).minusSeconds(120).toString());
        sale.put("tillClockAtSendUtc", Instant.now(clock).toString());
        sale.put("shiftId", trader.shiftId());
        sale.put("cashierId", trader.cashier().id());
        sale.put("unlockedOffline", false);
        sale.put("snapshotVersion", "test");
        var line = new HashMap<String, Object>();
        line.put("dailyMealOptionId", optionId);
        line.put("name", "Wrap");
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

    private static JsonNode sync(Trader trader, JsonNode grant, Map<String, Object> sale) throws Exception {
        var result = trader.side().mvc().perform(on(post("/pos/offline/sales"), trader.till())
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("grants", List.of(grant.get("grant").asText()), "sales", List.of(sale)))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("results").get(0);
    }
}
