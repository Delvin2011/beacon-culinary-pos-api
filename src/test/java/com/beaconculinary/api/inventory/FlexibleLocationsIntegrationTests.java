package com.beaconculinary.api.inventory;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderLine;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.settings.SiteSettings;
import com.beaconculinary.api.settings.SiteSettingsFixture;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.LocationFixture;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-5c: flexible stock locations (docs/2026-11-21-t0-5c-locations-api-contract.md). The two
 * seeded locations behave as before; renaming them changes nothing but names; a third location
 * (Coffee Station) works for requests, sales, waste, stock take and the stock views; every
 * refusal; ADMIN only; exactly one primary in the database.
 *
 * <p>Sales: only light meals deduct stock, and light meals are always at the KITCHEN station, so
 * a third location is exercised as the KITCHEN station's source (online here, offline in
 * O4OfflineSyncIntegrationTests); the BAR mapping is checked at the service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class FlexibleLocationsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COFFEE = "Coffee Station";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SiteSettings siteSettings;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private LightMealStockService lightMealStockService;
    @Autowired private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private RecipeRepository recipeRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;
    @Autowired private IngredientStockMovementRepository movementRepository;
    @Autowired private IngredientRepository ingredientRepository;
    @Autowired private StockRequestRepository stockRequestRepository;
    @Autowired private StockTakeRepository stockTakeRepository;
    @Autowired private WasteEntryRepository wasteEntryRepository;
    @Autowired private GrvRepository grvRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ShiftRepository shiftRepository;

    private String adminToken;
    private long primary;
    private long kitchen;
    private long flour;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        cleanUp();
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        primary = LocationFixture.primaryId(jdbc);
        kitchen = LocationFixture.kitchenId(jdbc);
        flour = createIngredient("T05c Flour", "KG");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        clock.reset();
    }

    private void cleanUp() {
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        stockTakeRepository.deleteAll();
        stockRequestRepository.deleteAll();
        wasteEntryRepository.deleteAll();
        movementRepository.deleteAll();
        grvRepository.deleteAll();
        ingredientRepository.deleteAll();
        shiftRepository.deleteAll();
        LocationFixture.reset(jdbc);
        SiteSettingsFixture.clearSavedAndHistory(jdbc);
        siteSettings.invalidate();
    }

    // --- 1. The two seeded locations ---------------------------------------------------------------

    @Test
    void seeded_mainStoreIsThePrimary_kitchenTheSecondary_andEveryJobUsesTheKitchen() throws Exception {
        var pickers = json(as(AuthTestHelper.loginAsCashier(mockMvc), get("/locations"), null).andExpect(status().isOk()));
        assertThat(pickers).hasSize(2);
        assertThat(pickers.get(0).get("name").asText()).isEqualTo("Main Store");
        assertThat(pickers.get(0).get("kind").asText()).isEqualTo("PRIMARY");
        assertThat(pickers.get(1).get("name").asText()).isEqualTo("Kitchen");
        assertThat(pickers.get(1).get("kind").asText()).isEqualTo("SECONDARY");

        var assignments = json(as(adminToken, get("/admin/locations/assignments"), null).andExpect(status().isOk()));
        assertThat(assignments.get("primary").get("name").asText()).isEqualTo("Main Store");
        assertThat(assignments.get("production").get("name").asText()).isEqualTo("Kitchen");
        for (var station : List.of("KITCHEN", "BAR", "NONE")) {
            assertThat(assignments.get("stations").get(station).get("id").asLong()).isEqualTo(kitchen);
        }
    }

    @Test
    void issueWithoutALocation_goesFromTheMainStoreToTheKitchen_asBefore() throws Exception {
        stock(primary, flour, "10");
        var request = createIssue(null, flour, "4");
        assertThat(request.get("locationId").asLong()).isEqualTo(kitchen);
        action(request.get("id").asLong(), flour, "4");

        assertStock(primary, flour, "6");
        assertStock(kitchen, flour, "4");
    }

    // --- 2. Renaming changes only names ------------------------------------------------------------

    @Test
    void renamedPrimaryAndKitchen_everyFlowStillUsesThem() throws Exception {
        rename(primary, "Central Store");
        rename(kitchen, "Back Kitchen");

        // GRV: into the primary.
        as(adminToken, post("/admin/grv"), Map.of("invoiceNumber", "T05C-1", "pricesIncludeVat", true,
                "supplierName", "T05c Mills", "lines", List.of(Map.of("ingredientId", flour,
                        "quantityReceived", new BigDecimal("20"), "unitPrice", new BigDecimal("10.00"),
                        "vatCode", "STANDARD")))).andExpect(status().is2xxSuccessful());
        assertStock(primary, flour, "20");

        // Request: primary -> the renamed Kitchen; the shortfall message names the primary.
        var request = createIssue(null, flour, "50");
        as(adminToken, post("/stock-requests/" + request.get("id").asLong() + "/action"),
                Map.of("lines", List.of(Map.of("ingredientId", flour, "actionedQuantity", new BigDecimal("50")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("actionedQuantity for ingredientId " + flour
                        + " exceeds available Central Store stock."));
        var small = createIssue(null, flour, "5");
        action(small.get("id").asLong(), flour, "5");
        assertStock(primary, flour, "15");
        assertStock(kitchen, flour, "5");

        // Planning labels its figures with the current names.
        var planning = json(as(adminToken, get("/admin/daily-planning/" + LocalDate.now(clock)
                + "/ingredient-requirements?period=Lunch"), null).andExpect(status().isOk()));
        assertThat(planning.get("primaryLocation").get("name").asText()).isEqualTo("Central Store");
        assertThat(planning.get("productionLocation").get("name").asText()).isEqualTo("Back Kitchen");

        // Waste and stock take at either location.
        waste(kitchen, flour, "1");
        assertStock(kitchen, flour, "4");
        approvedStockTake(primary, flour, "14");
        assertStock(primary, flour, "14");

        // A light-meal sale deducts at the renamed Kitchen.
        var sale = sellLightMeal(flour, "0.5");
        assertThat(consumedAt(sale)).containsOnly(kitchen);
        assertStock(kitchen, flour, "3.5");
    }

    // --- 3. A third location ------------------------------------------------------------------------

    @Test
    void coffeeStation_requestsFromThePrimary_sellsWastesAndCounts_andShowsInTheStockView() throws Exception {
        var coffee = createLocation(COFFEE);
        stock(primary, flour, "10");

        // Requested by Coffee Station, supplied by the primary.
        var request = createIssue(coffee, flour, "3");
        assertThat(request.get("locationName").asText()).isEqualTo(COFFEE);
        action(request.get("id").asLong(), flour, "3");
        assertStock(primary, flour, "7");
        assertStock(coffee, flour, "3");

        // Coffee Station as the KITCHEN station's source: a light-meal sale deducts there.
        assign(Map.of("stations", Map.of("KITCHEN", coffee)));
        var sale = sellLightMeal(flour, "1");
        assertThat(consumedAt(sale)).containsOnly(coffee);
        assertStock(coffee, flour, "2");
        assertStock(kitchen, flour, "0");

        // The till menu counts the meal at Coffee Station.
        assertThat(menuItem("T05c Scone").get("unitsAvailable").asInt()).isEqualTo(2);

        // Waste and a stock take (variance at Coffee Station only).
        waste(coffee, flour, "0.5");
        assertStock(coffee, flour, "1.5");
        var take = approvedStockTake(coffee, flour, "1");
        assertThat(take.get("locationName").asText()).isEqualTo(COFFEE);
        assertThat(new BigDecimal(take.get("lines").get(0).get("varianceQuantity").asText())).isEqualByComparingTo("-0.5");
        assertStock(coffee, flour, "1");
        assertStock(primary, flour, "7");

        // The ingredient's stock view lists all three, in order, adding up to the total.
        var view = json(as(adminToken, get("/admin/ingredients/" + flour + "/stock"), null).andExpect(status().isOk()));
        var names = new ArrayList<String>();
        view.get("byLocation").forEach(row -> names.add(row.get("locationName").asText()));
        assertThat(names).containsExactly("Main Store", "Kitchen", COFFEE);
        assertThat(new BigDecimal(view.get("totalStock").asText())).isEqualByComparingTo("8");
    }

    @Test
    void aBarStationLine_deductsFromTheBarStationsSource() throws Exception {
        var coffee = createLocation(COFFEE);
        assign(Map.of("stations", Map.of("BAR", coffee)));
        var sale = sellLightMeal(flour, "1");
        var recordedBy = userRepository.findById(AuthTestHelper.adminId(mockMvc)).orElseThrow();

        // Light meals are always at KITCHEN; mark this line BAR in memory and deduct again.
        var locations = transactions.execute(tx -> {
            var lines = entityManager.createQuery("SELECT l FROM OrderLine l WHERE l.order.id = :id", OrderLine.class)
                    .setParameter("id", sale).getResultList();
            lines.forEach(line -> line.setStation(Station.BAR));
            var written = lightMealStockService.deductForSale(lines, recordedBy, null);
            tx.setRollbackOnly();
            return written.stream().map(m -> m.getLocation().getId()).distinct().toList();
        });
        assertThat(locations).containsExactly(coffee);
    }

    // --- 4. Refusals --------------------------------------------------------------------------------

    @Test
    void issueRequestedByThePrimary_isRefused() throws Exception {
        as(adminToken, post("/stock-requests"), issueBody(primary, flour, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUE_LOCATION_NOT_SECONDARY"));
    }

    @Test
    void issueForAnInactiveLocation_isRefused() throws Exception {
        var coffee = createLocation(COFFEE);
        assertThat(json(as(adminToken, post("/admin/locations/" + coffee + "/deactivate"), null)
                .andExpect(status().isOk())).get("active").asBoolean()).isFalse();
        as(adminToken, post("/stock-requests"), issueBody(coffee, flour, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_INACTIVE"));
    }

    @Test
    void deactivatingThePrimary_isRefused() throws Exception {
        as(adminToken, post("/admin/locations/" + primary + "/deactivate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_DEACTIVATE_REFUSED"))
                .andExpect(jsonPath("$.details.reasons[0]").value("PRIMARY"));
    }

    @Test
    void deactivatingTheKitchen_isRefusedWhileItIsAssigned() throws Exception {
        as(adminToken, post("/admin/locations/" + kitchen + "/deactivate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reasons[0]").value("ASSIGNED"))
                .andExpect(jsonPath("$.details.assignedTo.length()").value(4));
    }

    @Test
    void deactivating_listsStockOpenRequestsAndOpenStockTakes_thenSucceedsOnceClear() throws Exception {
        var coffee = createLocation(COFFEE);
        stock(coffee, flour, "2");
        var request = createIssue(coffee, flour, "1");
        var take = json(as(adminToken, post("/stock-takes"), Map.of("locationId", coffee,
                "lines", List.of(Map.of("ingredientId", flour, "actualQuantity", new BigDecimal("2")))))
                .andExpect(status().isCreated()));

        var refusal = json(as(adminToken, post("/admin/locations/" + coffee + "/deactivate"), null)
                .andExpect(status().isConflict()));
        var reasons = new ArrayList<String>();
        refusal.get("details").get("reasons").forEach(r -> reasons.add(r.asText()));
        assertThat(reasons).containsExactly("HAS_STOCK", "OPEN_STOCK_REQUESTS", "OPEN_STOCK_TAKES");
        assertThat(refusal.get("details").get("stock").get(0).get("ingredientId").asLong()).isEqualTo(flour);
        assertThat(refusal.get("details").get("openStockRequestIds").get(0).asLong()).isEqualTo(request.get("id").asLong());
        assertThat(refusal.get("details").get("openStockTakeIds").get(0).asLong()).isEqualTo(take.get("id").asLong());

        // Clear it: cancel the request, cancel the count, waste the stock.
        as(adminToken, post("/stock-requests/" + request.get("id").asLong() + "/cancel"), null).andExpect(status().isOk());
        as(adminToken, post("/stock-takes/" + take.get("id").asLong() + "/cancel"), null).andExpect(status().isOk());
        waste(coffee, flour, "2");

        as(adminToken, post("/admin/locations/" + coffee + "/deactivate"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        var pickers = json(as(adminToken, get("/locations"), null));
        pickers.forEach(p -> assertThat(p.get("id").asLong()).isNotEqualTo(coffee));
        assertThat(json(as(adminToken, get("/locations?includeInactive=true"), null)).findValues("id").stream()
                .map(JsonNode::asLong)).contains(coffee);

        // And back.
        as(adminToken, post("/admin/locations/" + coffee + "/activate"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void duplicateNames_areRefused_ignoringCase() throws Exception {
        as(adminToken, post("/admin/locations"), Map.of("name", "kitchen"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_NAME_TAKEN"));
        var coffee = createLocation(COFFEE);
        as(adminToken, put("/admin/locations/" + coffee), Map.of("name", "MAIN STORE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_NAME_TAKEN"));
        as(adminToken, post("/admin/locations"), Map.of("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_INVALID"))
                .andExpect(jsonPath("$.details.fields.name").exists());
    }

    @Test
    void moreThanTheMaximum_isRefused() throws Exception {
        as(adminToken, put("/admin/settings"), Map.of("stock.locations.max-active", 3))
                .andExpect(status().isOk());
        createLocation(COFFEE);
        as(adminToken, post("/admin/locations"), Map.of("name", "T05c Pastry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_LIMIT_REACHED"))
                .andExpect(jsonPath("$.details.maxActive").value(3));
    }

    @Test
    void assigningThePrimaryOrAnInactiveLocation_isRefused() throws Exception {
        as(adminToken, put("/admin/locations/assignments"), Map.of("productionLocationId", primary))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_SECONDARY"));
        var coffee = createLocation(COFFEE);
        as(adminToken, post("/admin/locations/" + coffee + "/deactivate"), null).andExpect(status().isOk());
        as(adminToken, put("/admin/locations/assignments"), Map.of("stations", Map.of("BAR", coffee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_INACTIVE"));
    }

    // --- 5. ADMIN only, and history --------------------------------------------------------------

    @Test
    void changesAreAdminOnly_butEveryRoleCanListThePickers() throws Exception {
        for (var token : List.of(AuthTestHelper.loginAsStockAdmin(mockMvc), AuthTestHelper.loginAsHeadChef(mockMvc),
                AuthTestHelper.loginAsCashier(mockMvc))) {
            as(token, get("/admin/locations"), null).andExpect(status().isForbidden());
            as(token, post("/admin/locations"), Map.of("name", COFFEE)).andExpect(status().isForbidden());
            as(token, put("/admin/locations/assignments"), Map.of("productionLocationId", kitchen))
                    .andExpect(status().isForbidden());
            as(token, get("/locations"), null).andExpect(status().isOk());
        }
    }

    @Test
    void everyChange_isInTheHistory() throws Exception {
        var coffee = createLocation(COFFEE);
        rename(coffee, "Coffee Bar");
        assign(Map.of("stations", Map.of("BAR", coffee)));
        assign(Map.of("stations", Map.of("BAR", kitchen)));
        as(adminToken, post("/admin/locations/" + coffee + "/deactivate"), null).andExpect(status().isOk());

        var history = json(as(adminToken, get("/admin/locations/history?locationId=" + coffee), null)
                .andExpect(status().isOk()));
        var actions = new ArrayList<String>();
        history.get("content").forEach(entry -> actions.add(entry.get("action").asText()));
        assertThat(actions).containsExactly("DEACTIVATE", "ASSIGN", "UPDATE", "CREATE");
        var update = history.get("content").get(2);
        assertThat(update.get("before").get("name").asText()).isEqualTo(COFFEE);
        assertThat(update.get("after").get("name").asText()).isEqualTo("Coffee Bar");
        assertThat(history.get("content").get(1).get("role").asText()).isEqualTo("STATION_BAR");
        assertThat(history.get("content").get(1).get("previousLocationId").asLong()).isEqualTo(kitchen);

        // Saving the same values again records nothing.
        rename(coffee, "Coffee Bar");
        assertThat(json(as(adminToken, get("/admin/locations/history?locationId=" + coffee), null))
                .get("totalElements").asLong()).isEqualTo(4);
    }

    // --- 6. The database --------------------------------------------------------------------------

    @Test
    void theDatabase_keepsExactlyOneActivePrimary() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO [locations] ([name], [kind], [sort_order]) VALUES ('T05c Second', 'PRIMARY', 99)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE [locations] SET [kind] = 'SECONDARY' WHERE [id] = ?", primary))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE [locations] SET [active] = 0 WHERE [id] = ?", primary))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [locations] WHERE [kind] = 'PRIMARY' AND [active] = 1",
                Integer.class)).isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return PosFlowHelper.json(result);
    }

    private long createLocation(String name) throws Exception {
        var created = json(as(adminToken, post("/admin/locations"), Map.of("name", name)).andExpect(status().isCreated()));
        assertThat(created.get("kind").asText()).isEqualTo("SECONDARY");
        return created.get("id").asLong();
    }

    private void rename(long id, String name) throws Exception {
        as(adminToken, put("/admin/locations/" + id), Map.of("name", name)).andExpect(status().isOk());
    }

    private void assign(Map<String, Object> body) throws Exception {
        as(adminToken, put("/admin/locations/assignments"), body).andExpect(status().isOk());
    }

    private long createIngredient(String name, String unit) throws Exception {
        long drystock = 0;
        for (var node : json(as(adminToken, get("/count-sheet-categories"), null))) {
            if (node.get("name").asText().equalsIgnoreCase("DRYSTOCK")) {
                drystock = node.get("id").asLong();
            }
        }
        return json(as(adminToken, post("/admin/ingredients"), Map.of("name", name, "unit", unit,
                "countSheetCategoryId", drystock)).andExpect(status().isCreated())).get("id").asLong();
    }

    /** Opening stock at a location, as a stock take would leave it. */
    private void stock(long locationId, long ingredientId, String quantity) throws Exception {
        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredientRepository.findById(ingredientId).orElseThrow());
        movement.setMovementType(MovementType.RECEIVED);
        movement.setQuantity(new BigDecimal(quantity));
        movement.setLocation(locationRepository.findById(locationId).orElseThrow());
        movement.setSourceType(MovementSourceType.STOCK_TAKE);
        movement.setSourceId(0L);
        movement.setRecordedBy(userRepository.findById(AuthTestHelper.adminId(mockMvc)).orElseThrow());
        movementRepository.save(movement);
    }

    private void assertStock(long locationId, long ingredientId, String expected) {
        assertThat(movementRepository.sumQuantityByIngredientIdAndLocationId(ingredientId, locationId))
                .isEqualByComparingTo(expected);
    }

    private static Map<String, Object> issueBody(Long locationId, long ingredientId, String quantity) {
        var body = new HashMap<String, Object>();
        body.put("requestType", "ISSUE");
        if (locationId != null) {
            body.put("locationId", locationId);
        }
        body.put("lines", List.of(Map.of("ingredientId", ingredientId, "quantity", new BigDecimal(quantity))));
        return body;
    }

    private JsonNode createIssue(Long locationId, long ingredientId, String quantity) throws Exception {
        return json(as(adminToken, post("/stock-requests"), issueBody(locationId, ingredientId, quantity))
                .andExpect(status().isCreated()));
    }

    private void action(long requestId, long ingredientId, String quantity) throws Exception {
        as(adminToken, post("/stock-requests/" + requestId + "/action"), Map.of("lines",
                List.of(Map.of("ingredientId", ingredientId, "actionedQuantity", new BigDecimal(quantity)))))
                .andExpect(status().isOk());
    }

    private void waste(long locationId, long ingredientId, String quantity) throws Exception {
        as(adminToken, post("/admin/waste"), Map.of("ingredientId", ingredientId, "locationId", locationId,
                "quantity", new BigDecimal(quantity), "reason", "T05c spoilage")).andExpect(status().is2xxSuccessful());
    }

    private JsonNode approvedStockTake(long locationId, long ingredientId, String counted) throws Exception {
        var take = json(as(adminToken, post("/stock-takes"), Map.of("locationId", locationId,
                "lines", List.of(Map.of("ingredientId", ingredientId, "actualQuantity", new BigDecimal(counted)))))
                .andExpect(status().isCreated()));
        return json(as(adminToken, post("/stock-takes/" + take.get("id").asLong() + "/review"),
                Map.of("decision", "APPROVE")).andExpect(status().isOk()));
    }

    /** A light meal (T05c Scone) using {@code perUnit} of the ingredient, sold once for cash. */
    private long sellLightMeal(long ingredientId, String perUnit) throws Exception {
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var build = pos.createComponent("T05c Scone build", "0.00");
        as(adminToken, put("/admin/components/" + build + "/recipe"), Map.of("batchSize", 1,
                "lines", List.of(Map.of("ingredientId", ingredientId, "quantity", new BigDecimal(perUnit)))))
                .andExpect(status().isOk());
        as(adminToken, post("/admin/meal-catalog"), Map.of("name", "T05c Scone", "description", "d",
                "price", new BigDecimal("20.00"), "categoryId", CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"),
                "componentIds", List.of(build))).andExpect(status().isCreated());
        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        pos.openShift(cashier, "100.00");
        return pos.placeCashOrder(cashier, "20.00", new LineReq(menuItem("T05c Scone").get("id").asLong(), 1, List.of()));
    }

    private JsonNode menuItem(String name) throws Exception {
        for (var option : json(mockMvc.perform(get("/menu/today"))).get("options")) {
            if (option.get("name").asText().equals(name)) {
                return option;
            }
        }
        throw new AssertionError(name + " is not on the till menu");
    }

    /** The locations the order's CONSUMED movements were written at. */
    private List<Long> consumedAt(long orderId) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.[location_id] FROM [ingredient_stock_movements] m
                JOIN [order_lines] l ON l.[id] = m.[source_id]
                WHERE m.[source_type] = 'ORDER_LINE' AND m.[movement_type] = 'CONSUMED' AND l.[order_id] = ?""",
                Long.class, orderId);
    }
}
