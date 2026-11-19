package com.beaconculinary.api.offline;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.IngredientRepository;
import com.beaconculinary.api.inventory.IngredientStockMovement;
import com.beaconculinary.api.inventory.IngredientStockMovementRepository;
import com.beaconculinary.api.inventory.LocationRepository;
import com.beaconculinary.api.inventory.MovementSourceType;
import com.beaconculinary.api.inventory.MovementType;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Cashier;
import com.beaconculinary.api.support.O3Tills.Till;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.TestLocale;
import com.beaconculinary.api.users.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** O4: POST /pos/offline/sales and the recording path (contract sections 2 to 6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O4OfflineSyncIntegrationTests {
    private static final String PREFIX = "O4 Sync ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private OfflineProperties properties;
    @Autowired private OfflineSequenceStore sequences;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;
    @Autowired private RecipeRepository recipeRepository;
    @Autowired private IngredientRepository ingredientRepository;
    @Autowired private IngredientStockMovementRepository movementRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private UserRepository userRepository;

    private O3Tills tills;
    private String adminPwd;
    private Cashier cashier;
    private Till t1;
    private Till t2;
    private String cashierToken;
    private long shiftId;
    private JsonNode grant;
    private long stewId;
    private long beefExtraId;
    private long accountId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 0));
        today = LocalDate.now(clock);
        properties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        cashier = tills.createCashier();
        t1 = tills.registerTill();
        t2 = tills.registerTill();

        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var beef = pos.createComponent("O4S Beef", "15.00");
        var lunch = pos.periodId("lunch");
        stewId = pos.createDailyOption(lunch, pos.createMealCatalog("O4S Stew", "50.00", beef), 10);
        beefExtraId = pos.createDailyComponentStock(lunch, beef, 5);
        var account = mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + "Finance", "department", "Finance", "contactNumber", "021 555 0101",
                        "contactEmail", "o4sync@example.test")))).andReturn();
        accountId = body(account).get("id").asLong();

        cashierToken = tills.pinLogin(cashier, t1);
        shiftId = tills.openShift(cashierToken, t1);
        grant = grantFor(cashierToken, t1);
        clock.setTime(LocalTime.of(12, 30));
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        properties.setAccountsEnabled(true);
        jdbc.update("DELETE FROM [sale_exceptions]");
        jdbc.execute("DISABLE TRIGGER trg_z_readings_insert_only ON z_readings; DELETE FROM z_readings WHERE closed_by IN "
                + "(SELECT id FROM users WHERE email = '" + AuthTestHelper.ADMIN_EMAIL + "') AND definition_version = 99; "
                + "ENABLE TRIGGER trg_z_readings_insert_only ON z_readings;");
        jdbc.update("DELETE FROM [receipt_reprints]");
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        movementRepository.deleteAll();
        ingredientRepository.deleteAll();
        jdbc.update("DELETE FROM [accounts] WHERE [name] LIKE ?", PREFIX + "%");
        tills.cleanUp();
        clock.reset();
    }

    // ---- created and duplicates ----

    @Test
    void aCleanSale_isCreated_alreadyCollected_withTheTillsNumbers() throws Exception {
        var sale = sale(7, 20);

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(result.get("result").asText()).isEqualTo("CREATED");
        assertThat(result.get("receiptNumber").asText()).isEqualTo(receipt(7));
        assertThat(result.get("collectionNumber").asText()).isEqualTo("T1-7");
        var orderId = result.get("orderId").asLong();
        var row = jdbc.queryForMap("SELECT * FROM [orders] WHERE [id] = ?", orderId);
        assertThat(row.get("status")).isEqualTo("COLLECTED");
        assertThat(row.get("recorded_offline")).isEqualTo(true);
        assertThat(((Number) row.get("shift_id")).longValue()).isEqualTo(shiftId);
        assertThat(((Number) row.get("cashier_id")).longValue()).isEqualTo(cashier.id());
        assertThat(row.get("offline_seq")).isEqualTo(7);
        assertThat(row.get("total").toString()).isEqualTo("50.00");
        assertThat(row.get("vat_included_amount").toString()).isEqualTo("6.52");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [order_station_status] WHERE [order_id] = ?", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForList("SELECT [to_status] FROM [order_status_events] WHERE [order_id] = ?", String.class, orderId))
                .containsExactly("COLLECTED");
        assertThat(portions(stewId)).isEqualTo(9);
        assertThat(sequences.nextFor(UUID.fromString(t1.id()), today)).isEqualTo(8);

        var fetched = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + adminPwd)).andReturn());
        assertThat(fetched.get("receiptNumber").asText()).isEqualTo(receipt(7));
        assertThat(fetched.get("soldAt").asText()).isEqualTo(soldAt(20).toString());
        assertThat(fetched.get("recordedOffline").asBoolean()).isTrue();
    }

    @Test
    void aResend_isADuplicate_andADifferentResend_isFlaggedOnce() throws Exception {
        var sale = sale(1, 20);
        var first = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        var again = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);
        assertThat(again.get("result").asText()).isEqualTo("DUPLICATE");
        assertThat(again.get("orderId").asLong()).isEqualTo(first.get("orderId").asLong());
        assertThat(again.get("exceptions")).isEmpty();
        assertThat(portions(stewId)).isEqualTo(9);

        sale.put("total", new BigDecimal("55.00"));
        var differs = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);
        assertThat(codes(differs)).containsExactly("DUPLICATE_DIFFERS");
        assertThat(codes(result(sync(t1, List.of(grantToken()), List.of(sale)), 0))).isEmpty();
        assertThat(exceptionCount(first.get("orderId").asLong(), "DUPLICATE_DIFFERS")).isEqualTo(1);
    }

    @Test
    void aSaleFirstSentOnline_comesBackAsADuplicate_withTheSlipsNumbers() throws Exception {
        var clientOrderId = UUID.randomUUID();
        var online = mockMvc.perform(on(post("/orders"), t1).header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "clientOrderId", clientOrderId.toString(),
                        "payments", List.of(Map.of("method", "CASH", "amount", 50, "amountTendered", 50)),
                        "lines", List.of(Map.of("dailyMealOptionId", stewId, "quantity", 1, "extras", List.of())))))).andReturn();
        assertThat(online.getResponse().getStatus()).as(online.getResponse().getContentAsString()).isEqualTo(201);
        var sale = sale(1, 20);
        sale.put("clientOrderId", clientOrderId.toString());

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(result.get("result").asText()).isEqualTo("DUPLICATE");
        assertThat(result.get("orderId").asLong()).isEqualTo(body(online).get("id").asLong());
        assertThat(result.get("receiptNumber").asText()).isEqualTo(receipt(1));
        assertThat(result.get("collectionNumber").asText()).isEqualTo("T1-1");
        var fetched = body(mockMvc.perform(get("/orders/" + result.get("orderId").asLong())
                .header("Authorization", "Bearer " + adminPwd)).andReturn());
        assertThat(fetched.get("receiptNumber").asText()).isEqualTo(receipt(1));
        assertThat(fetched.get("recordedOffline").asBoolean()).isFalse();
        assertThat(sequences.nextFor(UUID.fromString(t1.id()), today)).isEqualTo(2);
        // A second resend changes nothing.
        assertThat(result(sync(t1, List.of(grantToken()), List.of(sale)), 0).get("receiptNumber").asText()).isEqualTo(receipt(1));
    }

    // ---- invalid and refused ----

    @Test
    void anInvalidSale_doesntStopTheRestOfTheBatch() throws Exception {
        var missingLines = sale(2, 21);
        missingLines.put("lines", List.of());
        var unreadable = sale(3, 22);
        unreadable.put("soldAt", "not a time");
        var unknownGrant = sale(4, 23);
        unknownGrant.put("grantId", UUID.randomUUID().toString());

        var results = body(sync(t1, List.of(grantToken()), List.of(sale(1, 20), missingLines, unreadable, unknownGrant, sale(5, 24))))
                .get("results");

        assertThat(results.get(0).get("result").asText()).isEqualTo("CREATED");
        assertThat(results.get(1).get("result").asText()).isEqualTo("INVALID");
        assertThat(results.get(1).get("error").asText()).contains("line");
        assertThat(results.get(2).get("result").asText()).isEqualTo("INVALID");
        assertThat(results.get(2).get("clientOrderId").asText()).isEqualTo(unreadable.get("clientOrderId"));
        assertThat(results.get(3).get("result").asText()).isEqualTo("INVALID");
        assertThat(results.get(4).get("result").asText()).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [orders] WHERE [offline_grant_id] = ?", Integer.class,
                grant.get("grantId").asText())).isEqualTo(2);
    }

    @Test
    void refusals_badSignature_anotherTillsGrant_noCookie_andBadBatches() throws Exception {
        var token = grantToken();
        var parts = token.split("\\.");
        var tampered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        assertThat(result(sync(t1, List.of(tampered), List.of(sale(1, 20))), 0).get("result").asText()).isEqualTo("REFUSED");

        var other = tills.createCashier();
        var otherToken = tills.pinLogin(other, t2);
        clock.setTime(LocalTime.of(12, 0));
        tills.openShift(otherToken, t2);
        var t2Grant = grantFor(otherToken, t2);
        clock.setTime(LocalTime.of(12, 30));
        var t2Sale = sale(1, 20);
        t2Sale.put("grantId", t2Grant.get("grantId").asText());
        var refused = result(sync(t1, List.of(t2Grant.get("grant").asText()), List.of(t2Sale)), 0);
        assertThat(refused.get("result").asText()).isEqualTo("REFUSED");
        assertThat(refused.get("code").asText()).isEqualTo("GRANT_INVALID");

        assertThat(sync(null, List.of(token), List.of(sale(1, 20))).getResponse().getStatus()).isEqualTo(403);
        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(sale(i + 1, 20));
        }
        var batch = sync(t1, List.of(token), tooMany);
        assertThat(batch.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(batch).get("code").asText()).isEqualTo("INVALID_BATCH");
        var unreadable = mockMvc.perform(on(post("/pos/offline/sales"), t1).contentType(MediaType.APPLICATION_JSON)
                .content("{not json")).andReturn();
        assertThat(body(unreadable).get("code").asText()).isEqualTo("INVALID_BATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [orders] WHERE [recorded_offline] = 1", Integer.class)).isZero();
    }

    // ---- late sales ----

    @Test
    void afterTheMealPeriod_andAfterMidnight_theSaleIsStillRecorded() throws Exception {
        var evening = sale(1, 0);
        evening.put("soldAt", local(today, 23, 30).toString());
        var night = sale(2, 0);
        night.put("soldAt", local(today.plusDays(1), 0, 10).toString());
        clock.setInstant(local(today.plusDays(1), 1, 0));
        evening.put("tillClockAtSendUtc", clock.instant().toString());
        night.put("tillClockAtSendUtc", clock.instant().toString());

        var results = body(sync(t1, List.of(grantToken()), List.of(evening, night))).get("results");

        assertThat(codes(results.get(0))).containsExactly("PERIOD_CLOSED");
        assertThat(codes(results.get(1))).containsExactlyInAnyOrder("PERIOD_CLOSED", "ITEM_UNAVAILABLE");
        assertThat(orderDate(results.get(0))).isEqualTo(today);
        assertThat(orderDate(results.get(1))).isEqualTo(today.plusDays(1));
        assertThat(detail(results.get(1).get("orderId").asLong(), "ITEM_UNAVAILABLE").get("reason").asText()).isEqualTo("OTHER_DAY");
    }

    @Test
    void afterTheShiftClosed_theSaleStaysOnItsShift() throws Exception {
        var close = mockMvc.perform(on(post("/shifts/" + shiftId + "/close"), t1).header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();
        assertThat(close.getResponse().getStatus()).as(close.getResponse().getContentAsString()).isEqualTo(200);

        var result = result(sync(t1, List.of(grantToken()), List.of(sale(1, 20))), 0);

        assertThat(codes(result)).containsExactly("LATE_AFTER_SHIFT_CLOSE");
        assertThat(jdbc.queryForObject("SELECT [shift_id] FROM [orders] WHERE [id] = ?", Long.class, result.get("orderId").asLong()))
                .isEqualTo(shiftId);
    }

    @Test
    void afterAZ_theSaleIsBookedToTheNextOpenDay() throws Exception {
        insertZ(today);
        insertZ(today.plusDays(1));

        var result = result(sync(t1, List.of(grantToken()), List.of(sale(1, 20))), 0);

        assertThat(codes(result)).containsExactly("LATE_AFTER_Z");
        var row = jdbc.queryForMap("SELECT [order_date], [booked_trading_day], [sold_at] FROM [orders] WHERE [id] = ?",
                result.get("orderId").asLong());
        assertThat(row.get("order_date").toString()).isEqualTo(today.plusDays(2).toString());
        assertThat(row.get("booked_trading_day").toString()).isEqualTo(today.plusDays(2).toString());
        assertThat(((java.sql.Timestamp) row.get("sold_at")).toLocalDateTime()).isEqualTo(LocalDateTime.ofInstant(soldAt(20), ZoneOffset.UTC));
        assertThat(result.get("orderNumber").asInt()).isEqualTo(1);
        assertThat(detail(result.get("orderId").asLong(), "LATE_AFTER_Z").get("bookedDay").asText())
                .isEqualTo(today.plusDays(2).toString());
    }

    // ---- what the till charged ----

    @Test
    void oversoldPortionsAndExtras_goToZero_withTheShortfall() throws Exception {
        jdbc.update("UPDATE [daily_meal_options] SET [portions_remaining] = 1 WHERE [id] = ?", stewId);
        var sale = sale(1, 20);
        sale.put("lines", List.of(line(stewId, 3, "50.00", List.of(extra(beefExtraId, 6, "15.00")))));
        sale.put("subtotal", new BigDecimal("240.00"));
        sale.put("total", new BigDecimal("240.00"));
        sale.put("vatIncludedAmount", new BigDecimal("31.30"));
        sale.put("payments", List.of(cash("240.00")));

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(codes(result)).containsExactly("OVERSOLD", "OVERSOLD");
        assertThat(portions(stewId)).isZero();
        assertThat(jdbc.queryForObject("SELECT [buffer_remaining] FROM [daily_component_stock] WHERE [id] = ?", Integer.class, beefExtraId))
                .isZero();
        var details = jdbc.queryForList("SELECT [detail] FROM [sale_exceptions] WHERE [order_id] = ? AND [code] = 'OVERSOLD'",
                String.class, result.get("orderId").asLong());
        assertThat(details).anyMatch(d -> d.contains("\"shortfall\":2")).anyMatch(d -> d.contains("\"shortfall\":1"));
    }

    @Test
    void theTillsPriceStands_aDifferenceIsFlagged() throws Exception {
        var sale = sale(1, 20);
        sale.put("lines", List.of(line(stewId, 1, "45.00", List.of())));
        sale.put("subtotal", new BigDecimal("45.00"));
        sale.put("total", new BigDecimal("45.00"));
        sale.put("vatIncludedAmount", new BigDecimal("5.87"));
        sale.put("payments", List.of(cash("45.00")));

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(codes(result)).containsExactly("PRICE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT [total] FROM [orders] WHERE [id] = ?", BigDecimal.class, result.get("orderId").asLong()))
                .isEqualByComparingTo("45.00");
    }

    @Test
    void anUnknownItem_isRecordedByNameAndPrice() throws Exception {
        var sale = sale(1, 20);
        var unknown = line(987654321L, 1, "50.00", List.of());
        unknown.put("name", "Mystery pie");
        sale.put("lines", List.of(unknown));

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(codes(result)).containsExactly("UNKNOWN_ITEM");
        var line = jdbc.queryForMap("SELECT [daily_meal_option_id], [name], [unit_price] FROM [order_lines] WHERE [order_id] = ?",
                result.get("orderId").asLong());
        assertThat(line.get("daily_meal_option_id")).isNull();
        assertThat(line.get("name")).isEqualTo("Mystery pie");
    }

    @Test
    void vat_isRecomputed_andAPrintedDifferenceIsFlagged() throws Exception {
        var sale = sale(1, 20);
        sale.put("vatIncludedAmount", new BigDecimal("7.00"));

        var result = result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        assertThat(codes(result)).containsExactly("VAT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT [vat_included_amount] FROM [orders] WHERE [id] = ?", BigDecimal.class,
                result.get("orderId").asLong())).isEqualByComparingTo("6.52");
    }

    @Test
    void paymentProblems_areFlagged_butRecorded() throws Exception {
        var shortCash = sale(1, 20);
        shortCash.put("payments", List.of(cash("40.00"), Map.of("method", "CARD", "amount", 0, "cardReference", "X")));
        var noReference = sale(2, 21);
        noReference.put("payments", List.of(Map.of("method", "CARD", "amount", new BigDecimal("50.00"))));
        var totals = sale(3, 22);
        totals.put("subtotal", new BigDecimal("60.00"));
        totals.put("total", new BigDecimal("60.00"));
        totals.put("vatIncludedAmount", new BigDecimal("7.83"));
        totals.put("payments", List.of(cash("60.00")));

        var results = body(sync(t1, List.of(grantToken()), List.of(shortCash, noReference, totals))).get("results");

        assertThat(codes(results.get(0))).containsExactly("PAYMENT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [order_payments] WHERE [order_id] = ?", Integer.class,
                results.get(0).get("orderId").asLong())).isEqualTo(1);
        assertThat(codes(results.get(1))).containsExactly("CARD_REFERENCE_MISSING");
        assertThat(codes(results.get(2))).containsExactly("TOTALS_MISMATCH");
    }

    @Test
    void accountSales_awaitApproval_andTheCapIsPerAccountPerGrant() throws Exception {
        var first = sale(1, 20);
        first.put("payments", List.of(account(accountId, "50.00")));
        var second = sale(2, 21);
        second.put("lines", List.of(line(stewId, 3, "50.00", List.of())));
        second.put("subtotal", new BigDecimal("150.00"));
        second.put("total", new BigDecimal("150.00"));
        second.put("vatIncludedAmount", new BigDecimal("19.57"));
        second.put("payments", List.of(account(accountId, "150.00")));
        jdbc.update("INSERT INTO [accounts] ([name], [department], [contact_number], [contact_email], [active]) "
                + "VALUES (?, 'X', '021 555 0102', 'o4old@example.test', 0)", PREFIX + "Old");
        var inactiveId = jdbc.queryForObject("SELECT [id] FROM [accounts] WHERE [name] = ?", Long.class, PREFIX + "Old");
        var inactive = sale(3, 22);
        inactive.put("payments", List.of(account(inactiveId, "50.00")));
        var unknown = sale(4, 23);
        unknown.put("payments", List.of(account(99999999L, "50.00")));

        var results = body(sync(t1, List.of(grantToken()), List.of(first, second, inactive, unknown))).get("results");

        assertThat(codes(results.get(0))).containsExactly("ACCOUNT_APPROVAL_PENDING");
        assertThat(codes(results.get(1))).containsExactlyInAnyOrder("ACCOUNT_APPROVAL_PENDING", "ACCOUNT_OVER_CAP");
        assertThat(codes(results.get(2))).containsExactlyInAnyOrder("ACCOUNT_APPROVAL_PENDING", "ACCOUNT_INACTIVE");
        assertThat(codes(results.get(3))).containsExactlyInAnyOrder("ACCOUNT_APPROVAL_PENDING", "ACCOUNT_INACTIVE");
        assertThat(detail(results.get(3).get("orderId").asLong(), "ACCOUNT_INACTIVE").get("reason").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT [account_id] FROM [order_payments] WHERE [order_id] = ?", Long.class,
                results.get(3).get("orderId").asLong())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [order_payments] WHERE [order_id] = ? AND [authorized_by] IS NULL",
                Integer.class, results.get(0).get("orderId").asLong())).isEqualTo(1);
    }

    // ---- the till and the grant ----

    @Test
    void aRevokedTill_isAccepted_andFlagged() throws Exception {
        var revoke = mockMvc.perform(post("/admin/devices/" + t1.id() + "/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"stolen\"}")).andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

        var result = result(sync(t1, List.of(grantToken()), List.of(sale(1, 20))), 0);

        assertThat(codes(result)).containsExactly("DEVICE_REVOKED");
    }

    @Test
    void aReplacedGrant_stillCovers_theSalesMadeBefore() throws Exception {
        var oldToken = grantToken();
        var oldGrantId = grant.get("grantId").asText();
        clock.setTime(LocalTime.of(12, 10));
        grantFor(cashierToken, t1);
        clock.setTime(LocalTime.of(12, 30));
        var before = sale(1, 5);
        before.put("grantId", oldGrantId);
        var after = sale(2, 20);
        after.put("grantId", oldGrantId);

        var results = body(sync(t1, List.of(oldToken), List.of(before, after))).get("results");

        assertThat(codes(results.get(0))).isEmpty();
        assertThat(codes(results.get(1))).containsExactly("OUTSIDE_GRANT");
        assertThat(detail(results.get(1).get("orderId").asLong(), "OUTSIDE_GRANT").get("revokeReason").asText()).isEqualTo("REPLACED");
    }

    @Test
    void clockSkew_futureSales_grantMismatch_andOfflineUnlock_areFlagged() throws Exception {
        var skewed = sale(1, 20);
        skewed.put("tillClockAtSendUtc", clock.instant().plusSeconds(600).toString());
        var future = sale(2, 0);
        future.put("soldAt", clock.instant().plusSeconds(300).toString());
        var mismatch = sale(3, 21);
        mismatch.put("shiftId", shiftId + 1000);
        var unlocked = sale(4, 22);
        unlocked.put("unlockedOffline", true);

        var results = body(sync(t1, List.of(grantToken()), List.of(skewed, future, mismatch, unlocked))).get("results");

        assertThat(codes(results.get(0))).containsExactly("CLOCK_SKEW");
        assertThat(codes(results.get(1))).containsExactly("FUTURE_SALE");
        assertThat(codes(results.get(2))).containsExactly("GRANT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT [shift_id] FROM [orders] WHERE [id] = ?", Long.class,
                results.get(2).get("orderId").asLong())).isEqualTo(shiftId);
        assertThat(results.get(3).get("exceptions").get(0).get("severity").asText()).isEqualTo("INFO");
        assertThat(codes(results.get(3))).containsExactly("UNLOCKED_OFFLINE");
    }

    @Test
    void aNumberClash_isRecorded_withTheReceiptNumberAsPrinted() throws Exception {
        var first = sale(9, 20);
        var second = sale(9, 21);

        var results = body(sync(t1, List.of(grantToken()), List.of(first, second))).get("results");

        assertThat(codes(results.get(0))).isEmpty();
        assertThat(codes(results.get(1))).containsExactly("OFFLINE_NUMBER_CLASH");
        assertThat(results.get(1).get("receiptNumber").asText()).isEqualTo(receipt(9));
        assertThat(jdbc.queryForObject("SELECT [offline_seq] FROM [orders] WHERE [id] = ?", Integer.class,
                results.get(1).get("orderId").asLong())).isNull();
    }

    // ---- stock ----

    @Test
    void lightMealIngredients_areConsumed_datedWhenSold() throws Exception {
        var bread = createIngredient(PREFIX + "Bread");
        var build = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository)
                .createComponent("O4S Toastie build", "0.00");
        mockMvc.perform(put("/admin/components/" + build + "/recipe").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("batchSize", 1,
                        "lines", List.of(Map.of("ingredientId", bread, "quantity", new BigDecimal("2"))))))).andReturn();
        mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("name", "O4S Toastie",
                        "description", "d", "price", new BigDecimal("30.00"),
                        "categoryId", CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"), "componentIds", List.of(build)))))
                .andReturn();
        var menu = MAPPER.readTree(mockMvc.perform(get("/menu/today")).andReturn().getResponse().getContentAsString());
        long toastie = 0;
        for (var option : menu.get("options")) {
            if ("O4S Toastie".equals(option.get("name").asText())) {
                toastie = option.get("id").asLong();
            }
        }
        var sale = sale(1, 20);
        sale.put("lines", List.of(line(toastie, 1, "30.00", List.of())));
        sale.put("subtotal", new BigDecimal("30.00"));
        sale.put("total", new BigDecimal("30.00"));
        sale.put("vatIncludedAmount", new BigDecimal("3.91"));
        sale.put("payments", List.of(cash("30.00")));

        result(sync(t1, List.of(grantToken()), List.of(sale)), 0);

        var movement = jdbc.queryForMap("SELECT [quantity], [occurred_at], [movement_type] FROM [ingredient_stock_movements] "
                + "WHERE [ingredient_id] = ? AND [movement_type] = 'CONSUMED'", bread);
        assertThat(((BigDecimal) movement.get("quantity"))).isEqualByComparingTo("-2");
        assertThat(((java.sql.Timestamp) movement.get("occurred_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.ofInstant(soldAt(20), ZoneOffset.UTC));
    }

    // ---- two tills at once ----

    @Test
    void twoTillsSyncingAtOnce_getDistinctOrderNumbers_andShareThePortions() throws Exception {
        var other = tills.createCashier();
        var otherToken = tills.pinLogin(other, t2);
        clock.setTime(LocalTime.of(12, 0));
        var otherShift = tills.openShift(otherToken, t2);
        var t2Grant = grantFor(otherToken, t2);
        clock.setTime(LocalTime.of(12, 30));
        jdbc.update("UPDATE [daily_meal_options] SET [portions_remaining] = 6 WHERE [id] = ?", stewId);

        var t1Sales = new ArrayList<Map<String, Object>>();
        var t2Sales = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 5; i++) {
            t1Sales.add(sale(i, 10 + i));
            var s = sale(i, 10 + i);
            s.put("grantId", t2Grant.get("grantId").asText());
            s.put("shiftId", otherShift);
            s.put("cashierId", other.id());
            s.put("offlineReceiptNumber", "T2-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-000" + i);
            t2Sales.add(s);
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> a = () -> sync(t1, List.of(grantToken()), t1Sales);
            Callable<MvcResult> b = () -> sync(t2, List.of(t2Grant.get("grant").asText()), t2Sales);
            var futures = pool.invokeAll(List.of(a, b));
            var numbers = new HashSet<Integer>();
            for (var future : futures) {
                for (var result : body(future.get()).get("results")) {
                    assertThat(result.get("result").asText()).startsWith("CREATED");
                    numbers.add(result.get("orderNumber").asInt());
                }
            }
            assertThat(numbers).hasSize(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(portions(stewId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [sale_exceptions] WHERE [code] = 'OVERSOLD'", Integer.class)).isEqualTo(4);
        assertThat(sequences.nextFor(UUID.fromString(t1.id()), today)).isEqualTo(6);
        assertThat(sequences.nextFor(UUID.fromString(t2.id()), today)).isEqualTo(6);
    }

    // ---- helpers ----

    private JsonNode grantFor(String token, Till till) throws Exception {
        var result = mockMvc.perform(on(post("/pos/offline/grant"), till).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        var body = body(result);
        if (till == t1) {
            grant = body;
        }
        return body;
    }

    private String grantToken() {
        return grant.get("grant").asText();
    }

    private Instant soldAt(int minuteAfterNoon) {
        return local(today, 12, minuteAfterNoon);
    }

    private static Instant local(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(TestLocale.ZONE).toInstant();
    }

    private String receipt(int seq) {
        return "T1-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("-%04d", seq);
    }

    private Map<String, Object> sale(int seq, int minuteAfterNoon) {
        var sale = new HashMap<String, Object>();
        sale.put("clientOrderId", UUID.randomUUID().toString());
        sale.put("grantId", grant.get("grantId").asText());
        sale.put("offlineSeq", seq);
        sale.put("offlineReceiptNumber", receipt(seq));
        sale.put("collectionNumber", "T1-" + seq);
        sale.put("soldAt", soldAt(minuteAfterNoon).toString());
        sale.put("tillClockAtSendUtc", clock.instant().toString());
        sale.put("shiftId", shiftId);
        sale.put("cashierId", cashier.id());
        sale.put("unlockedOffline", false);
        sale.put("snapshotVersion", "test");
        sale.put("lines", List.of(line(stewId, 1, "50.00", List.of())));
        sale.put("subtotal", new BigDecimal("50.00"));
        sale.put("total", new BigDecimal("50.00"));
        sale.put("vatRate", new BigDecimal("0.1500"));
        sale.put("vatIncludedAmount", new BigDecimal("6.52"));
        sale.put("payments", List.of(cash("50.00")));
        return sale;
    }

    private static Map<String, Object> line(long optionId, int quantity, String unitPrice, List<Map<String, Object>> extras) {
        var line = new HashMap<String, Object>();
        line.put("dailyMealOptionId", optionId);
        line.put("name", "Line " + optionId);
        line.put("station", "KITCHEN");
        line.put("quantity", quantity);
        line.put("unitPrice", new BigDecimal(unitPrice));
        line.put("lineTotal", new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity)));
        line.put("extras", extras);
        return line;
    }

    private static Map<String, Object> extra(long stockId, int quantity, String priceDelta) {
        return Map.of("dailyComponentStockId", stockId, "name", "Extra " + stockId, "priceDelta", new BigDecimal(priceDelta),
                "quantity", quantity, "lineTotal", new BigDecimal(priceDelta).multiply(BigDecimal.valueOf(quantity)));
    }

    private static Map<String, Object> cash(String amount) {
        return Map.of("method", "CASH", "amount", new BigDecimal(amount), "amountTendered", new BigDecimal(amount),
                "changeDue", BigDecimal.ZERO);
    }

    private static Map<String, Object> account(long id, String amount) {
        return Map.of("method", "ACCOUNT", "amount", new BigDecimal(amount), "accountId", id);
    }

    private MvcResult sync(Till till, List<String> grants, List<Map<String, Object>> sales) throws Exception {
        return mockMvc.perform(on(post("/pos/offline/sales"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("grants", grants, "sales", sales)))).andReturn();
    }

    private static JsonNode result(MvcResult result, int index) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("results").get(index);
    }

    private static List<String> codes(JsonNode result) {
        var codes = new ArrayList<String>();
        result.get("exceptions").forEach(e -> codes.add(e.get("code").asText()));
        return codes;
    }

    private int portions(long optionId) {
        return jdbc.queryForObject("SELECT [portions_remaining] FROM [daily_meal_options] WHERE [id] = ?", Integer.class, optionId);
    }

    private LocalDate orderDate(JsonNode result) {
        return jdbc.queryForObject("SELECT [order_date] FROM [orders] WHERE [id] = ?", LocalDate.class, result.get("orderId").asLong());
    }

    private int exceptionCount(long orderId, String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [sale_exceptions] WHERE [order_id] = ? AND [code] = ?", Integer.class,
                orderId, code);
    }

    private JsonNode detail(long orderId, String code) throws Exception {
        return MAPPER.readTree(jdbc.queryForObject("SELECT TOP (1) [detail] FROM [sale_exceptions] WHERE [order_id] = ? AND [code] = ?",
                String.class, orderId, code));
    }

    private void insertZ(LocalDate day) {
        jdbc.update("""
                INSERT INTO [z_readings] ([z_number], [trading_day], [closed_by], [closed_at], [definition_version],
                    [gross_sales], [net_sales], [snapshot])
                SELECT COALESCE(MAX([z_number]), 0) + 1, ?, (SELECT [id] FROM [users] WHERE [email] = ?), SYSUTCDATETIME(), 99,
                    0, 0, '{}' FROM [z_readings]""", java.sql.Date.valueOf(day), AuthTestHelper.ADMIN_EMAIL);
    }

    private long createIngredient(String name) throws Exception {
        var categories = MAPPER.readTree(mockMvc.perform(get("/count-sheet-categories").header("Authorization", "Bearer " + adminPwd))
                .andReturn().getResponse().getContentAsString());
        long drystock = 0;
        for (var node : categories) {
            if (node.get("name").asText().equalsIgnoreCase("DRYSTOCK")) {
                drystock = node.get("id").asLong();
            }
        }
        var result = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", name, "unit", "EACH", "countSheetCategoryId", drystock)))).andReturn();
        var id = body(result).get("id").asLong();
        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredientRepository.findById(id).orElseThrow());
        movement.setMovementType(MovementType.RECEIVED);
        movement.setQuantity(new BigDecimal("50"));
        movement.setLocation(locationRepository.findByNameIgnoreCase("Kitchen").orElseThrow());
        movement.setSourceType(MovementSourceType.STOCK_TAKE);
        movement.setSourceId(0L);
        movement.setRecordedBy(userRepository.findById(AuthTestHelper.adminId(mockMvc)).orElseThrow());
        movementRepository.save(movement);
        return id;
    }
}
