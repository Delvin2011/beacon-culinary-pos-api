package com.beaconculinary.api.tenancy.crosscustomer;

import com.beaconculinary.api.bar.BarEventBroadcaster;
import com.beaconculinary.api.board.PublicBoardEventBroadcaster;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * T1-10: area by area, nothing one customer has can be seen, changed or listed at the other. Alpha and bravo are built
 * from the same baseline, so their ids line up (the first shift, order and user have the same id at both), and each
 * test makes the same names at both (emails, cashier names, till names and serials, item names, location names,
 * client order ids) with different values, so a leak would show as the other customer's value. Each test makes its
 * own rows under a unique marker and puts back any shared row it changes, so it passes on its own. Sales, shifts and
 * tills stay (the fixture databases are throwaway; every multi-mode test counts only its own rows).
 * docs/2026-11-27-cross-customer-tests.md
 */
class CrossCustomerAreasIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OfflineProperties offline;
    @Autowired
    private BarEventBroadcaster bar;
    @Autowired
    private PublicBoardEventBroadcaster board;
    @Autowired
    private Clock clock;

    private MultiCustomerFixture fixture;
    private Side alpha;
    private Side bravo;
    private String marker;
    private final List<Runnable> restore = new ArrayList<>();

    /** One customer: its address, its database and its admin's token. */
    private record Side(String slug, MockMvc mvc, JdbcTemplate db, String adminToken) {
    }

    /** A cashier trading at one customer: a till, a cashier signed in with a PIN, and an open shift. */
    private record Trader(Side side, O3Tills tills, O3Tills.Till till, O3Tills.Cashier cashier, String token, long shiftId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = MultiCustomerFixture.get();
        alpha = side("alpha");
        bravo = side("bravo");
        marker = "T110 " + Integer.toString(ThreadLocalRandom.current().nextInt(1 << 30), 36);
    }

    @AfterEach
    void tearDown() {
        for (int i = restore.size() - 1; i >= 0; i--) {
            restore.get(i).run();
        }
        restore.clear();
        offline.setEnabled(false);
    }

    // --- password and PIN sign-in ---

    @Test
    void signIn_alphasPasswordAndPin_meanNothingAtBravo_whereTheSameEmailAndCashierExist() throws Exception {
        var email = marker.replace(' ', '-').toLowerCase() + "@cross.test";
        var alphaPerson = person(alpha, email, "Alpha-Pass-1", marker + " alpha");
        person(bravo, email, "Bravo-Pass-2", marker + " bravo");
        var alphaCashier = cashier(alpha, marker + " cashier", "482913");
        cashier(bravo, marker + " cashier", "731905");

        var atAlpha = login(alpha, email, "Alpha-Pass-1");
        assertThat(atAlpha.getResponse().getStatus()).isEqualTo(200);
        assertThat(me(alpha, token(atAlpha)).get("id").asLong()).isEqualTo(alphaPerson);
        assertThat(login(bravo, email, "Alpha-Pass-1").getResponse().getStatus()).as("alpha's password at bravo").isEqualTo(401);
        var atBravo = login(bravo, email, "Bravo-Pass-2");
        assertThat(atBravo.getResponse().getStatus()).isEqualTo(200);
        assertThat(me(bravo, token(atBravo)).get("name").asText()).as("bravo's own person").isEqualTo(marker + " bravo");

        assertThat(pinLogin(alpha, alphaCashier, "482913").getResponse().getStatus()).isEqualTo(200);
        assertThat(pinLogin(bravo, alphaCashier, "482913").getResponse().getStatus()).as("alpha's cashier and PIN at bravo")
                .isNotEqualTo(200);
    }

    @Test
    void loginTiles_listOnlyTheCustomersOwnCashiers() throws Exception {
        cashier(alpha, marker + " alpha tile", "482913");
        cashier(bravo, marker + " bravo tile", "731905");

        var atAlpha = alpha.mvc().perform(get("/users/login-tiles").param("screen", "POS")).andReturn().getResponse().getContentAsString();
        var atBravo = bravo.mvc().perform(get("/users/login-tiles").param("screen", "POS")).andReturn().getResponse().getContentAsString();

        assertThat(atAlpha).contains(marker + " alpha tile").doesNotContain(marker + " bravo tile");
        assertThat(atBravo).contains(marker + " bravo tile").doesNotContain(marker + " alpha tile");
    }

    // --- devices (tills) ---

    @Test
    void tills_withTheSameNameAndSerial_areEachCustomersOwn() throws Exception {
        var serial = "X" + marker.substring(5).toUpperCase();
        var alphaTill = registerTill(alpha, marker + " till", serial);
        var bravoTill = registerTill(bravo, marker + " till", serial);
        assertThat(alphaTill.getResponse().getStatus()).isEqualTo(201);
        assertThat(bravoTill.getResponse().getStatus()).as("the same name and serial are free at bravo").isEqualTo(201);
        var alphaId = body(alphaTill).get("id").asText();
        var bravoId = body(bravoTill).get("id").asText();
        restore.add(() -> forget(alpha, marker + " till"));
        restore.add(() -> forget(bravo, marker + " till"));

        for (var side : List.of(alpha, bravo)) {
            var devices = body(admin(side, get("/admin/devices")));
            var named = new ArrayList<String>();
            devices.forEach(device -> {
                if ((marker + " till").equals(device.path("name").asText())) {
                    named.add(device.get("id").asText());
                }
            });
            assertThat(named).as("tills listed at " + side.slug()).containsExactly(side == alpha ? alphaId : bravoId);
        }
        assertThat(alpha.mvc().perform(on(get("/pos/till-status"), tillCookie(bravoTill, marker + " till"))
                .header("Authorization", "Bearer " + alpha.adminToken())).andReturn().getResponse().getContentAsString())
                .as("bravo's till cookie at alpha").doesNotContain(bravoId);
    }

    // --- shifts ---

    @Test
    void shifts_closingAlphasLeavesBravosOpen_andNeitherReadsTheOther() throws Exception {
        var a = trader(alpha);
        var b = trader(bravo);

        var closed = a.side().mvc().perform(on(post("/shifts/" + a.shiftId() + "/close"), a.till())
                .header("Authorization", "Bearer " + a.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":500.00}")).andReturn();
        assertThat(closed.getResponse().getStatus()).as(closed.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(alpha.db().queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, a.shiftId())).isEqualTo("CLOSED");
        assertThat(bravo.db().queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, b.shiftId())).isEqualTo("OPEN");
        var alphasAtBravo = admin(bravo, get("/shifts/" + a.shiftId() + "/summary"));
        assertThat(alphasAtBravo.getResponse().getContentAsString()).as("alpha's shift id at bravo")
                .doesNotContain(a.cashier().name());
        assertThat(admin(bravo, get("/admin/shifts")).getResponse().getContentAsString())
                .contains(b.cashier().name()).doesNotContain(a.cashier().name());
    }

    // --- sales, orders, payments and receipts ---

    @Test
    void sales_theSameClientOrderId_isANewSaleAtEach_andNeitherReadsOrListsTheOther() throws Exception {
        var item = marker + " stew";
        var a = trader(alpha);
        var b = trader(bravo);
        var alphaOption = readyOption(alpha, item, "50.00");
        var bravoOption = readyOption(bravo, item, "70.00");
        var clientOrderId = UUID.randomUUID().toString();

        var atAlpha = sell(a, alphaOption, "50.00", clientOrderId);
        var atBravo = sell(b, bravoOption, "70.00", clientOrderId);
        assertThat(atAlpha.getResponse().getStatus()).as(atAlpha.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(atBravo.getResponse().getStatus()).as("bravo has never seen this sale: created, not replayed")
                .isEqualTo(201);
        assertThat(body(atBravo).get("total").decimalValue()).isEqualByComparingTo("70.00");
        var alphaOrder = body(atAlpha);

        var alphasIdAtBravo = admin(bravo, get("/orders/" + alphaOrder.get("id").asLong()));
        if (alphasIdAtBravo.getResponse().getStatus() == 200) {
            assertThat(body(alphasIdAtBravo).get("cashierName").asText()).as("the same id at bravo is bravo's own order")
                    .isNotEqualTo(a.cashier().name());
        } else {
            assertThat(alphasIdAtBravo.getResponse().getStatus()).isEqualTo(404);
        }
        var todayAtBravo = admin(bravo, get("/orders/today")).getResponse().getContentAsString();
        assertThat(todayAtBravo).contains(b.cashier().name()).doesNotContain(a.cashier().name());
        assertThat(admin(bravo, post("/admin/orders/" + alphaOrder.get("id").asLong() + "/reprint-receipt"))
                .getResponse().getContentAsString()).as("a reprint at bravo is never alpha's receipt")
                .doesNotContain(a.cashier().name());
        assertThat(count(bravo, "SELECT COUNT(*) FROM [orders] WHERE [client_order_id] = ?", clientOrderId)).isEqualTo(1);
        assertThat(count(alpha, "SELECT COUNT(*) FROM [orders] WHERE [client_order_id] = ?", clientOrderId)).isEqualTo(1);
    }

    // --- manager PIN and authorisations ---

    @Test
    void managerAuthorisation_alphasPinAndToken_meanNothingAtBravo() throws Exception {
        adminPin(alpha, "135791");
        adminPin(bravo, "246802");
        var a = trader(alpha);
        var b = trader(bravo);

        assertThat(authorize(b, "135791").getResponse().getStatus()).as("alpha's manager PIN at bravo").isNotEqualTo(200);
        var alphaToken = body(authorize(a, "135791")).get("authorizationToken").asText();

        // A short count needs a manager's authorisation: alpha's token is refused at bravo, and bravo's own works.
        var withAlphas = closeShort(b, alphaToken);
        assertThat(withAlphas.getResponse().getStatus()).as("alpha's token at bravo").isNotEqualTo(200);
        assertThat(bravo.db().queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, b.shiftId())).isEqualTo("OPEN");
        assertThat(count(alpha, "SELECT COUNT(*) FROM [authorization_tokens] WHERE [token] = ?", alphaToken))
                .as("alpha's token is untouched at alpha").isEqualTo(1);
        var bravoToken = body(authorize(b, "246802")).get("authorizationToken").asText();
        var withBravos = closeShort(b, bravoToken);
        assertThat(withBravos.getResponse().getStatus()).as(withBravos.getResponse().getContentAsString()).isEqualTo(200);
    }

    // --- menus, prices and meal periods (there are no PLU codes: an item is its name and price) ---

    @Test
    void menu_theSameItem_hasEachCustomersOwnPrice_andMealPeriodsAreEachCustomersOwn() throws Exception {
        var item = marker + " curry";
        readyOption(alpha, item, "55.00");
        readyOption(bravo, item, "75.00");
        var a = trader(alpha);
        var b = trader(bravo);

        assertThat(price(menu(a), item)).isEqualByComparingTo("55.00");
        assertThat(price(menu(b), item)).isEqualByComparingTo("75.00");

        var period = marker + " supper";
        var alphaPeriod = createPeriod(alpha, period, "22:00", "22:30");
        var bravoPeriod = createPeriod(bravo, period, "22:40", "23:10");
        restore.add(() -> deactivatePeriod(alpha, alphaPeriod));
        restore.add(() -> deactivatePeriod(bravo, bravoPeriod));
        assertThat(periodStart(alpha, period)).isEqualTo("22:00");
        assertThat(periodStart(bravo, period)).as("bravo's own times for the same name").isEqualTo("22:40");
    }

    // --- locations and stock requests ---

    @Test
    void locationsAndStockRequests_areEachCustomersOwn() throws Exception {
        var location = marker + " store";
        var alphaLocation = createLocation(alpha, location);
        var bravoLocation = createLocation(bravo, location);
        restore.add(() -> deactivateLocation(alpha, alphaLocation));
        restore.add(() -> deactivateLocation(bravo, bravoLocation));
        var renamed = admin(alpha, put("/admin/locations/" + alphaLocation).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", location + " renamed"))));
        assertThat(renamed.getResponse().getStatus()).as(renamed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(bravo.db().queryForObject("SELECT [name] FROM [locations] WHERE [id] = ?", String.class, bravoLocation))
                .as("bravo's location of the same name").isEqualTo(location);
        assertThat(admin(bravo, get("/admin/locations")).getResponse().getContentAsString()).doesNotContain(location + " renamed");

        var ingredient = marker + " rice";
        var alphaRequest = stockRequest(alpha, ingredient(alpha, ingredient), "3");
        var bravoRequest = stockRequest(bravo, ingredient(bravo, ingredient), "8");
        var alphasAtBravo = admin(bravo, get("/stock-requests/" + alphaRequest));
        if (alphasAtBravo.getResponse().getStatus() == 200) {
            // The same id at bravo is bravo's own request: its lines are bravo's rows, never alpha's 3.
            var shown = new ArrayList<BigDecimal>();
            body(alphasAtBravo).get("lines").forEach(line -> shown.add(line.get("requestedQuantity").decimalValue().stripTrailingZeros()));
            var bravos = bravo.db().queryForList("SELECT [requested_quantity] FROM [stock_request_lines] WHERE [stock_request_id] = ?",
                    BigDecimal.class, alphaRequest).stream().map(BigDecimal::stripTrailingZeros).toList();
            assertThat(shown).as("alpha's request id at bravo").containsExactlyInAnyOrderElementsOf(bravos);
        } else {
            assertThat(alphasAtBravo.getResponse().getStatus()).isEqualTo(404);
        }
        var listedAtBravo = body(admin(bravo, get("/stock-requests")));
        var bravoIds = new ArrayList<Long>();
        listedAtBravo.forEach(request -> bravoIds.add(request.get("id").asLong()));
        assertThat(bravoIds).contains(bravoRequest);
        assertThat(count(alpha, "SELECT COUNT(*) FROM [stock_request_lines] l JOIN [ingredients] i ON i.[id] = l.[ingredient_id] "
                + "WHERE i.[name] = ?", ingredient)).isEqualTo(1);
        assertThat(count(bravo, "SELECT COUNT(*) FROM [stock_request_lines] l JOIN [ingredients] i ON i.[id] = l.[ingredient_id] "
                + "WHERE i.[name] = ? AND l.[requested_quantity] = 8", ingredient)).isEqualTo(1);
    }

    // --- staff, roles and invites ---

    @Test
    void staff_theSameEmail_isEachCustomersOwnPerson() throws Exception {
        var email = marker.replace(' ', '-').toLowerCase() + "@staff.test";
        var alphaStaff = createStaff(alpha, marker + " alpha clerk", email);
        var bravoStaff = createStaff(bravo, marker + " bravo clerk", email);

        assertThat(admin(alpha, get("/admin/staff")).getResponse().getContentAsString())
                .contains(marker + " alpha clerk").doesNotContain(marker + " bravo clerk");
        assertThat(admin(bravo, get("/admin/staff")).getResponse().getContentAsString())
                .contains(marker + " bravo clerk").doesNotContain(marker + " alpha clerk");
        var alphasIdAtBravo = admin(bravo, get("/admin/staff/" + alphaStaff));
        assertThat(alphasIdAtBravo.getResponse().getContentAsString()).doesNotContain(marker + " alpha clerk");
        var changed = admin(alpha, put("/admin/staff/" + alphaStaff).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", marker + " alpha renamed", "role", "STOCK_CLERK", "email", email))));
        assertThat(changed.getResponse().getStatus()).as(changed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(bravo.db().queryForObject("SELECT [name] FROM [users] WHERE [id] = ?", String.class, bravoStaff))
                .isEqualTo(marker + " bravo clerk");
    }

    // --- site settings, receipt settings and branding ---

    @Test
    void settings_aChangeAtAlpha_isNotSeenAtBravo_evenOnceBravosAreCached() throws Exception {
        var key = "auth.invites.expiry-hours";
        assertThat(setting(bravo, key)).as("read once, so bravo's settings are cached").isEqualTo("72");
        restore.add(() -> resetSetting(alpha, key));
        var saved = admin(alpha, put("/admin/settings").contentType(MediaType.APPLICATION_JSON).content("{\"" + key + "\":24}"));
        assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(setting(alpha, key)).isEqualTo("24");
        assertThat(setting(bravo, key)).as("bravo's own, from its own cache").isEqualTo("72");

        var footer = marker + " thanks";
        restore.add(() -> alpha.db().update("UPDATE [receipt_settings] SET [footer_text] = NULL WHERE [id] = 1"));
        alpha.db().update("UPDATE [receipt_settings] SET [footer_text] = ? WHERE [id] = 1", footer);
        assertThat(admin(alpha, get("/admin/receipt-settings")).getResponse().getContentAsString()).contains(footer);
        assertThat(admin(bravo, get("/admin/receipt-settings")).getResponse().getContentAsString()).doesNotContain(footer);
    }

    // --- reports and the dashboard ---

    @Test
    void reportsAndDashboard_alphasSale_isNotInBravosFigures_evenFromTheCache() throws Exception {
        var item = marker + " pie";
        var a = trader(alpha);
        var alphaOption = readyOption(alpha, item, "45.00");
        var today = LocalDate.now(clock).toString();
        var bravoReading = report(bravo, today);
        var bravoTopSellers = topSellers(bravo);

        assertThat(sell(a, alphaOption, "45.00", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(201);

        assertThat(topSellers(alpha)).as("alpha's own figures").contains(item);
        assertThat(topSellers(bravo)).as("bravo's figures, unchanged by alpha's sale").doesNotContain(item)
                .isEqualTo(bravoTopSellers);
        assertThat(report(bravo, today)).as("bravo's X reading is unchanged by alpha's sale").isEqualTo(bravoReading);
    }

    // --- the kitchen, bar, board and order streams ---

    @Test
    void publicBoardAndBarStream_showOnlyTheCustomersOwn() throws Exception {
        var item = marker + " soup";
        var a = trader(alpha);
        var alphaOption = readyOption(alpha, item, "40.00");
        var bravoBoard = boardOrders(bravo);

        var sale = sell(a, alphaOption, "40.00", UUID.randomUUID().toString());
        assertThat(sale.getResponse().getStatus()).isEqualTo(201);
        var orderNumber = body(sale).get("orderNumber").asInt();
        assertThat(boardOrders(alpha)).contains("\"orderNumber\":" + orderNumber);
        assertThat(boardOrders(bravo)).as("bravo's board is unchanged by alpha's order").isEqualTo(bravoBoard);

        var barStream = bravo.mvc().perform(get("/bar/orders/stream").header("Authorization", "Bearer " + bravo.adminToken())
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        var boardStream = bravo.mvc().perform(get("/public/board/stream").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        try {
            CustomerContext.runAs(fixture.customer("alpha"), () -> {
                bar.broadcast(Map.of("marker", "alphas-bar"));
                board.broadcast(Map.of("marker", "alphas-board"));
            });
            CustomerContext.runAs(fixture.customer("bravo"), () -> {
                bar.broadcast(Map.of("marker", "bravos-bar"));
                board.broadcast(Map.of("marker", "bravos-board"));
            });
            assertThat(barStream.getResponse().getContentAsString()).contains("bravos-bar").doesNotContain("alphas-bar");
            assertThat(boardStream.getResponse().getContentAsString()).contains("bravos-board").doesNotContain("alphas-board");
        } finally {
            barStream.getRequest().getAsyncContext().complete();
            boardStream.getRequest().getAsyncContext().complete();
        }
    }

    // --- offline grant, snapshot and sync (the grant and sync are MultiCustomerOfflineSyncIntegrationTests) ---

    @Test
    void offlineSnapshot_hasEachCustomersOwnItemsAndPrices() throws Exception {
        offline.setEnabled(true);
        var item = marker + " wrap";
        readyOption(alpha, item, "35.00");
        readyOption(bravo, item, "65.00");
        var a = trader(alpha);
        var b = trader(bravo);

        assertThat(price(snapshot(a), item)).isEqualByComparingTo("35.00");
        assertThat(price(snapshot(b), item)).as("bravo's own price for the same item").isEqualByComparingTo("65.00");
    }

    // --- anything stored outside the customer databases ---

    @Test
    void platformDatabase_holdsOnlyTheDirectory_whateverTheCustomersDo() throws Exception {
        var platform = fixture.platform();
        var before = platformRows(platform);
        var a = trader(alpha);
        var b = trader(bravo);
        sell(a, readyOption(alpha, marker + " bun", "20.00"), "20.00", UUID.randomUUID().toString());
        sell(b, readyOption(bravo, marker + " bun", "20.00"), "20.00", UUID.randomUUID().toString());

        assertThat(platform.queryForList("SELECT name FROM sys.tables WHERE is_ms_shipped = 0", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "tenants", "tenant_hosts", "tenant_databases",
                        "provisioning_runs");
        assertThat(platformRows(platform)).as("trading at both customers wrote nothing to the platform database")
                .isEqualTo(before);
    }

    // --- helpers: customers ---

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

    private MvcResult admin(Side side, MockHttpServletRequestBuilder request) throws Exception {
        return side.mvc().perform(request.header("Authorization", "Bearer " + side.adminToken())).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static int count(Side side, String sql, Object... args) {
        return side.db().queryForObject(sql, Integer.class, args);
    }

    // --- helpers: people ---

    private long person(Side side, String email, String password, String name) {
        var id = side.db().queryForObject("INSERT INTO [users] ([name], [email], [password], [role], [active]) OUTPUT INSERTED.[id] "
                + "VALUES (?, ?, ?, 'STOCK_CLERK', 1)", Long.class, name, email, ENCODER.encode(password));
        restore.add(() -> TestStaffCleanup.delete(side.db(), List.of(id)));
        return id;
    }

    private long cashier(Side side, String name, String pin) {
        var id = side.db().queryForObject("INSERT INTO [users] ([name], [role], [pin_hash], [active]) OUTPUT INSERTED.[id] "
                + "VALUES (?, 'CASHIER', ?, 1)", Long.class, name, ENCODER.encode(pin));
        restore.add(() -> TestStaffCleanup.delete(side.db(), List.of(id)));
        return id;
    }

    private void adminPin(Side side, String pin) {
        side.db().update("UPDATE [users] SET [pin_hash] = ? WHERE [email] = ?", ENCODER.encode(pin), AuthTestHelper.ADMIN_EMAIL);
        restore.add(() -> side.db().update("UPDATE [users] SET [pin_hash] = NULL WHERE [email] = ?", AuthTestHelper.ADMIN_EMAIL));
    }

    private static MvcResult login(Side side, String email, String password) throws Exception {
        return side.mvc().perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private static MvcResult pinLogin(Side side, long cashierId, String pin) throws Exception {
        return side.mvc().perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashierId, "pin", pin)))).andReturn();
    }

    private static String token(MvcResult result) throws Exception {
        return body(result).get("token").asText();
    }

    private static JsonNode me(Side side, String token) throws Exception {
        return body(side.mvc().perform(get("/auth/me").header("Authorization", "Bearer " + token)).andReturn());
    }

    private long createStaff(Side side, String name, String email) throws Exception {
        var created = admin(side, post("/admin/staff").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(
                Map.of("name", name, "role", "STOCK_CLERK", "email", email, "password", "Staff-Pass-" + side.slug()))));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        var id = body(created).get("id").asLong();
        restore.add(() -> TestStaffCleanup.delete(side.db(), List.of(id)));
        return id;
    }

    // --- helpers: tills, shifts and sales ---

    private MvcResult registerTill(Side side, String name, String serial) throws Exception {
        return admin(side, post("/admin/devices").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", name, "serialNumber", serial))));
    }

    private static O3Tills.Till tillCookie(MvcResult registered, String name) throws Exception {
        jakarta.servlet.http.Cookie cookie = null;
        for (var header : registered.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("deviceToken=")) {
                cookie = new jakarta.servlet.http.Cookie("deviceToken", header.substring("deviceToken=".length(), header.indexOf(';')));
            }
        }
        return new O3Tills.Till(body(registered).get("id").asText(), "", name, cookie);
    }

    private void forget(Side side, String tillName) {
        side.db().update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] = ?", tillName);
        side.db().update("DELETE FROM [devices] WHERE [name] = ?", tillName);
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

    private static MvcResult sell(Trader trader, long optionId, String amount, String clientOrderId) throws Exception {
        var order = Map.of("clientOrderId", clientOrderId,
                "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal(amount), "amountTendered", new BigDecimal(amount))));
        return trader.side().mvc().perform(on(post("/orders"), trader.till()).header("Authorization", "Bearer " + trader.token())
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(order))).andReturn();
    }

    private static MvcResult authorize(Trader trader, String pin) throws Exception {
        return trader.side().mvc().perform(on(post("/admin/authorize"), trader.till())
                .header("Authorization", "Bearer " + trader.token()).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("pin", pin)))).andReturn();
    }

    /** Closes the trader's shift R100 short, with this manager authorisation for the variance. */
    private static MvcResult closeShort(Trader trader, String authorizationToken) throws Exception {
        var body = Map.of("countedCash", new BigDecimal("400.00"), "varianceAuthorization",
                Map.of("reasonCode", "CASH_COUNTING_ERROR", "authorizationToken", authorizationToken));
        return trader.side().mvc().perform(on(post("/shifts/" + trader.shiftId() + "/close"), trader.till())
                .header("Authorization", "Bearer " + trader.token()).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();
    }

    // --- helpers: menus, settings, stock, reports and offline ---

    private static JsonNode menu(Trader trader) throws Exception {
        return body(trader.side().mvc().perform(on(get("/menu/today"), trader.till())
                .header("Authorization", "Bearer " + trader.token())).andReturn()).get("options");
    }

    private static JsonNode snapshot(Trader trader) throws Exception {
        var result = trader.side().mvc().perform(on(get("/pos/offline/snapshot"), trader.till())
                .header("Authorization", "Bearer " + trader.token())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    /** The price of the item called {@code name} anywhere in this JSON (a menu or a snapshot). */
    private static BigDecimal price(JsonNode node, String name) {
        var found = new ArrayList<BigDecimal>();
        collectPrices(node, name, found);
        assertThat(found).as("one price for " + name).isNotEmpty();
        assertThat(found.stream().distinct().count()).as("every mention of " + name + " has one price").isEqualTo(1);
        return found.get(0);
    }

    private static void collectPrices(JsonNode node, String name, List<BigDecimal> found) {
        if (node.isObject()) {
            if (name.equals(node.path("name").asText()) && node.has("price")) {
                found.add(node.get("price").decimalValue());
            }
            node.forEach(child -> collectPrices(child, name, found));
        } else if (node.isArray()) {
            node.forEach(child -> collectPrices(child, name, found));
        }
    }

    private long createPeriod(Side side, String name, String start, String end) throws Exception {
        var created = admin(side, post("/admin/meal-periods").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", name, "startTime", start, "endTime", end))));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        return body(created).get("id").asLong();
    }

    private void deactivatePeriod(Side side, long id) {
        try {
            admin(side, post("/admin/meal-periods/" + id + "/deactivate"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String periodStart(Side side, String name) throws Exception {
        var periods = body(admin(side, get("/admin/meal-periods")));
        var starts = new ArrayList<String>();
        periods.forEach(period -> {
            if (name.equals(period.path("name").asText())) {
                starts.add(period.get("startTime").asText().substring(0, 5));
            }
        });
        assertThat(starts).as(name + " at " + side.slug()).hasSize(1);
        return starts.get(0);
    }

    private long createLocation(Side side, String name) throws Exception {
        var created = admin(side, post("/admin/locations").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", name))));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        return body(created).get("id").asLong();
    }

    private void deactivateLocation(Side side, long id) {
        try {
            admin(side, post("/admin/locations/" + id + "/deactivate"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The dashboard's top sellers (its figures, without when they were worked out). */
    private String topSellers(Side side) throws Exception {
        var result = admin(side, get("/admin/dashboard/sections/top-sellers"));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("data").toString();
    }

    /** The public board's orders (without the server's time). */
    private static String boardOrders(Side side) throws Exception {
        return body(side.mvc().perform(get("/public/board/today")).andReturn()).get("orders").toString();
    }

    private long ingredient(Side side, String name) {
        var category = side.db().queryForObject("INSERT INTO [count_sheet_categories] ([name]) OUTPUT INSERTED.[id] VALUES (?)",
                Long.class, name);
        return side.db().queryForObject("INSERT INTO [ingredients] ([name], [unit], [count_sheet_category_id]) OUTPUT INSERTED.[id] "
                + "VALUES (?, 'KG', ?)", Long.class, name, category);
    }

    private long stockRequest(Side side, long ingredientId, String quantity) throws Exception {
        var created = admin(side, post("/stock-requests").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(
                Map.of("requestType", "ORDER", "lines", List.of(Map.of("ingredientId", ingredientId, "quantity", new BigDecimal(quantity)))))));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        return body(created).get("id").asLong();
    }

    private String setting(Side side, String key) throws Exception {
        var settings = body(admin(side, get("/admin/settings"))).get("settings");
        for (var setting : settings) {
            if (key.equals(setting.get("key").asText())) {
                return setting.get("value").asText();
            }
        }
        throw new AssertionError("no setting " + key);
    }

    private void resetSetting(Side side, String key) {
        try {
            admin(side, post("/admin/settings/" + key + "/reset"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String report(Side side, String tradingDay) throws Exception {
        var result = admin(side, get("/admin/reports/x-reading").param("tradingDay", tradingDay));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        var reading = body(result);
        // The figures, without when it was run (meta, and the "printed at" time in its header).
        ((com.fasterxml.jackson.databind.node.ObjectNode) reading).remove("meta");
        return reading.toString().replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<time>");
    }

    private static Map<String, Long> platformRows(JdbcTemplate platform) {
        var counts = new java.util.TreeMap<String, Long>();
        for (var table : Set.of("tenants", "tenant_hosts", "tenant_databases", "provisioning_runs")) {
            counts.put(table, platform.queryForObject("SELECT COUNT_BIG(*) FROM [" + table + "]", Long.class));
        }
        return counts;
    }
}
