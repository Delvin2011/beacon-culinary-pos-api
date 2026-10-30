package com.beaconculinary.api.bar;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Part B: beverages, the bar station and the status engine. Data from the work order: Cappuccino
 * (Hot, R28, BAR), Rooibos Tea (Hot, R18, BAR), Coke 330ml (Cold, R15, NONE), Orange Juice 300ml
 * (Cold, R18, NONE), and Chicken Burger (Burgers, R55, KITCHEN). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class BarStationIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;
    private String kitchenToken;
    private String baristaToken;
    private long cappuccinoMeal;
    private long chickenBurgerMeal;
    private final Map<String, JsonNode> menu = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        kitchenToken = AuthTestHelper.loginAsKitchen(mockMvc);
        baristaToken = AuthTestHelper.loginAsBarista(mockMvc);

        var hot = CatalogTestHelper.categoryId(mockMvc, "Beverages > Hot beverages");
        var cold = CatalogTestHelper.categoryId(mockMvc, "Beverages > Cold beverages");
        cappuccinoMeal = createMeal("Cappuccino", "28.00", hot, null);
        createMeal("Rooibos Tea", "18.00", hot, null);
        createMeal("Coke 330ml", "15.00", cold, null);
        createMeal("Orange Juice 300ml", "18.00", cold, null);
        chickenBurgerMeal = createMeal("Chicken Burger", "55.00", CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"), null);

        for (var option : MAPPER.readTree(mockMvc.perform(get("/menu/today")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("options")) {
            menu.put(option.get("name").asText(), option);
        }
        as(cashierToken, post("/shifts/open"), "{\"openingFloat\":500.00}").andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long createMeal(String name, String price, long categoryId, String station) throws Exception {
        var body = new HashMap<String, Object>(Map.of("name", name, "price", price, "categoryId", categoryId));
        if (station != null) {
            body.put("station", station);
        }
        return json(as(adminToken, post("/admin/meal-catalog"), body).andExpect(status().isCreated())).get("id").asLong();
    }

    private long option(String name) {
        return menu.get(name).get("id").asLong();
    }

    private record Line(long dailyMealOptionId, int quantity, List<Object> extras) {
        Line(long dailyMealOptionId, int quantity) {
            this(dailyMealOptionId, quantity, List.of());
        }
    }

    private ResultActions submit(String total, Line... lines) throws Exception {
        var payment = Map.of("method", "CASH", "amount", total, "amountTendered", total);
        return as(cashierToken, post("/orders"), Map.of("payments", List.of(payment), "lines", List.of(lines)));
    }

    private JsonNode place(String total, Line... lines) throws Exception {
        return json(submit(total, lines).andExpect(status().isCreated()));
    }

    private ResultActions kitchen(long orderId, String status) throws Exception {
        return as(kitchenToken, patch("/kitchen/orders/" + orderId + "/status"), Map.of("status", status));
    }

    private ResultActions bar(long orderId, String status) throws Exception {
        return as(baristaToken, post("/bar/orders/" + orderId + "/status"), Map.of("status", status));
    }

    private String overall(long orderId) throws Exception {
        return json(as(cashierToken, get("/orders/" + orderId), null).andExpect(status().isOk())).get("status").asText();
    }

    private List<Integer> numbersOn(String token, String path) throws Exception {
        var numbers = new java.util.ArrayList<Integer>();
        for (var order : json(as(token, get(path), null).andExpect(status().isOk()))) {
            numbers.add(order.get("orderNumber").asInt());
        }
        return numbers;
    }

    private List<Integer> boardNumbers() throws Exception {
        var numbers = new java.util.ArrayList<Integer>();
        for (var order : json(mockMvc.perform(get("/public/board/today"))).get("orders")) {
            numbers.add(order.get("orderNumber").asInt());
        }
        return numbers;
    }

    private String authorizePin() throws Exception {
        return json(as(cashierToken, post("/admin/authorize"), Map.of("pin", "654321")).andExpect(status().isOk()))
                .get("authorizationToken").asText();
    }

    private ResultActions cancelWholeOrder(long orderId) throws Exception {
        var body = new HashMap<String, Object>(Map.of("scope", "WHOLE_ORDER", "reasonCode", "CUSTOMER_COMPLAINT",
                "authorizationToken", authorizePin()));
        return as(cashierToken, post("/orders/" + orderId + "/adjustments"), body);
    }

    // --- catalogue -----------------------------------------------------------------------------

    @Test
    void beverages_haveStationsByCategory_noPeriod_andStationRules() throws Exception {
        as(adminToken, get("/admin/meal-catalog").param("group", "BEVERAGES"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Cappuccino')].station").value("BAR"))
                .andExpect(jsonPath("$[?(@.name == 'Coke 330ml')].station").value("NONE"))
                .andExpect(jsonPath("$[?(@.name == 'Cappuccino')].availability").value("ALWAYS"));
        assertThat(menu.get("Cappuccino").get("mealPeriodId").isNull()).isTrue();
        assertThat(menu.get("Cappuccino").get("period").isNull()).isTrue();
        assertThat(menu.get("Cappuccino").get("group").asText()).isEqualTo("BEVERAGES");
        assertThat(menu.get("Coke 330ml").get("portionsRemaining").isNull()).isTrue();

        var hot = CatalogTestHelper.categoryId(mockMvc, "Beverages > Hot beverages");
        as(adminToken, post("/admin/meal-catalog"), Map.of("name", "Kitchen Tea", "price", "10.00", "categoryId", hot, "station", "KITCHEN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STATION_INVALID"));
        as(adminToken, post("/admin/meal-catalog"), Map.of("name", "Bar Burger", "price", "50.00",
                        "categoryId", CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"), "station", "BAR"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STATION_INVALID"));
        // A hot drink served without preparation is allowed.
        createMeal("Filter Coffee", "15.00", hot, "NONE");
    }

    // --- B9 orders -----------------------------------------------------------------------------

    @Test
    void order1_twoCappuccinos_isBarOnly() throws Exception {
        var order = place("56.00", new Line(option("Cappuccino"), 2));
        var id = order.get("id").asLong();
        var number = order.get("orderNumber").asInt();

        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("stations")).hasSize(1);
        assertThat(order.get("stations").get(0).get("station").asText()).isEqualTo("BAR");
        assertThat(order.get("stations").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("lines").get(0).get("station").asText()).isEqualTo("BAR");
        assertThat(numbersOn(baristaToken, "/bar/orders")).contains(number);
        assertThat(numbersOn(kitchenToken, "/kitchen/orders")).doesNotContain(number);
        assertThat(boardNumbers()).contains(number);

        // The barista finishes it: overall DONE, off the bar display, still on the board.
        bar(id, "IN_PROGRESS").andExpect(status().isOk());
        bar(id, "DONE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(overall(id)).isEqualTo("DONE");
        assertThat(numbersOn(baristaToken, "/bar/orders")).doesNotContain(number);
        assertThat(boardNumbers()).contains(number);
    }

    @Test
    void order2_oneCoke_isCollectedAtOnce_andNeverDisplayed() throws Exception {
        var order = place("15.00", new Line(option("Coke 330ml"), 1));
        var number = order.get("orderNumber").asInt();

        assertThat(order.get("status").asText()).isEqualTo("COLLECTED");
        assertThat(order.get("stations")).isEmpty();
        assertThat(order.get("lines").get(0).get("station").asText()).isEqualTo("NONE");
        assertThat(numbersOn(baristaToken, "/bar/orders")).doesNotContain(number);
        assertThat(numbersOn(kitchenToken, "/kitchen/orders")).doesNotContain(number);
        assertThat(boardNumbers()).doesNotContain(number);
        // A mistake on a cold-only order is a refund, not a void.
        cancelWholeOrder(order.get("id").asLong()).andExpect(status().isCreated());
        assertThat(overall(order.get("id").asLong())).isEqualTo("REFUNDED");
    }

    @Test
    void order3_burgerAndCappuccino_followsTheEngineTable() throws Exception {
        var order = place("83.00", new Line(option("Chicken Burger"), 1), new Line(option("Cappuccino"), 1));
        var id = order.get("id").asLong();
        assertThat(order.get("stations").findValuesAsText("station")).containsExactly("KITCHEN", "BAR");
        assertThat(overall(id)).isEqualTo("PENDING");

        // Each display shows only its own lines, plus the other station's progress.
        as(kitchenToken, get("/kitchen/orders"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].lines[*].optionName", contains("Chicken Burger")))
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].otherStations[0].station", contains("BAR")));
        as(baristaToken, get("/bar/orders"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].lines[*].optionName", contains("Cappuccino")))
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].otherStations[0].station", contains("KITCHEN")));

        bar(id, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.orderStatus").value("IN_PROGRESS"));
        assertThat(overall(id)).isEqualTo("IN_PROGRESS");
        bar(id, "DONE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(overall(id)).isEqualTo("IN_PROGRESS");
        kitchen(id, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.otherStations[0].status").value("DONE"));
        assertThat(overall(id)).isEqualTo("IN_PROGRESS");
        kitchen(id, "DONE").andExpect(status().isOk());
        assertThat(overall(id)).isEqualTo("DONE");

        var detail = json(as(cashierToken, get("/orders/" + id), null));
        assertThat(detail.get("stations").findValuesAsText("status")).containsExactly("DONE", "DONE");
        assertThat(detail.get("stations").get(1).get("updatedBy").get("name").asText()).isEqualTo("Canteen Barista");
        assertThat(detail.get("stations").get(0).get("updatedAt").asText()).endsWith("Z");

        // Station changes are logged with their station; the order's own changes without one.
        var events = orderStatusEventRepository.findAll().stream().filter(e -> e.getOrder().getId() == id).toList();
        assertThat(events.stream().filter(e -> e.getStation() != null)).hasSize(4);
        assertThat(events.stream().filter(e -> e.getStation() == null).map(e -> e.getToStatus().name()))
                .containsExactly("PENDING", "IN_PROGRESS", "DONE");
    }

    @Test
    void order3_voidIsAllowedWhileCreated_andRefusedOnceTheBarStarts() throws Exception {
        var created = place("83.00", new Line(option("Chicken Burger"), 1), new Line(option("Cappuccino"), 1)).get("id").asLong();
        cancelWholeOrder(created).andExpect(status().isCreated());
        assertThat(overall(created)).isEqualTo("VOIDED");
        // Nothing can move a voided order on.
        bar(created, "IN_PROGRESS").andExpect(status().isBadRequest());

        var started = place("83.00", new Line(option("Chicken Burger"), 1), new Line(option("Cappuccino"), 1)).get("id").asLong();
        bar(started, "IN_PROGRESS").andExpect(status().isOk());
        cancelWholeOrder(started).andExpect(status().isCreated());
        assertThat(overall(started)).isEqualTo("REFUNDED");
    }

    @Test
    void order4_cokeAndBurger_followsTheKitchenOnly() throws Exception {
        var order = place("70.00", new Line(option("Coke 330ml"), 1), new Line(option("Chicken Burger"), 1));
        var id = order.get("id").asLong();
        assertThat(order.get("stations").findValuesAsText("station")).containsExactly("KITCHEN");
        assertThat(numbersOn(baristaToken, "/bar/orders")).doesNotContain(order.get("orderNumber").asInt());

        as(kitchenToken, get("/kitchen/orders"), null)
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].lines[*].optionName", contains("Chicken Burger")))
                .andExpect(jsonPath("$[?(@.orderId == " + id + ")].otherStations[*]").isEmpty());
        kitchen(id, "IN_PROGRESS").andExpect(status().isOk());
        assertThat(overall(id)).isEqualTo("IN_PROGRESS");
        kitchen(id, "DONE").andExpect(status().isOk());
        assertThat(overall(id)).isEqualTo("DONE");
        // A bar-less order has no bar to move.
        bar(id, "IN_PROGRESS").andExpect(status().isNotFound());
    }

    @Test
    void stationSteps_areForwardOnly() throws Exception {
        var id = place("28.00", new Line(option("Cappuccino"), 1)).get("id").asLong();
        bar(id, "DONE").andExpect(status().isBadRequest());
        bar(id, "IN_PROGRESS").andExpect(status().isOk());
        bar(id, "PENDING").andExpect(status().isBadRequest());
        bar(id, "IN_PROGRESS").andExpect(status().isBadRequest());
    }

    @Test
    void cappuccinoOutToday_isUnavailable_andTheBaristaCanToggleIt() throws Exception {
        as(baristaToken, post("/admin/meal-catalog/" + cappuccinoMeal + "/out-today"), Map.of("out", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outToday").value(true));
        submit("28.00", new Line(option("Cappuccino"), 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));

        // The Barista only toggles beverages; kitchen staff can't toggle at all.
        as(baristaToken, post("/admin/meal-catalog/" + chickenBurgerMeal + "/out-today"), Map.of("out", true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_TODAY_NOT_PERMITTED"));
        as(kitchenToken, post("/admin/meal-catalog/" + cappuccinoMeal + "/out-today"), Map.of("out", false))
                .andExpect(status().isForbidden());

        as(baristaToken, post("/admin/meal-catalog/" + cappuccinoMeal + "/out-today"), Map.of("out", false))
                .andExpect(status().isOk());
        submit("28.00", new Line(option("Cappuccino"), 1)).andExpect(status().isCreated());
    }

    @Test
    void extraOnACappuccino_isRefused() throws Exception {
        submit("38.00", new Line(option("Cappuccino"), 1, List.of(Map.of("dailyComponentStockId", 999999, "quantity", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXTRAS_NOT_ALLOWED"));
    }

    // --- surfaces and permissions --------------------------------------------------------------

    @Test
    void barAndKitchenDisplays_areSeparate() throws Exception {
        as(kitchenToken, get("/bar/orders"), null).andExpect(status().isForbidden());
        as(baristaToken, get("/kitchen/orders"), null).andExpect(status().isForbidden());
        as(cashierToken, get("/bar/orders"), null).andExpect(status().isForbidden());
        as(adminToken, get("/bar/orders"), null).andExpect(status().isOk());
        as(baristaToken, get("/bar/orders"), null).andExpect(status().isOk());
        // A Barista can't sell yet.
        as(baristaToken, post("/shifts/open"), "{\"openingFloat\":100.00}").andExpect(status().isForbidden());
    }

    @Test
    void barLoginTiles_listBaristasAndAdmins() throws Exception {
        mockMvc.perform(get("/users/login-tiles").param("screen", "BAR")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Canteen Barista")))
                .andExpect(jsonPath("$[*].name", hasItem("Canteen Admin")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Kitchen Station"))));
    }

    @Test
    void streams_carryOnlyEachStationsOwnOrders() throws Exception {
        var kitchenStream = as(kitchenToken, get("/kitchen/orders/stream"), null).andExpect(request().asyncStarted()).andReturn();
        var barStream = as(baristaToken, get("/bar/orders/stream"), null).andExpect(request().asyncStarted()).andReturn();
        var boardStream = mockMvc.perform(get("/public/board/stream")).andExpect(request().asyncStarted()).andReturn();

        var barOnly = place("28.00", new Line(option("Cappuccino"), 1)).get("orderNumber").asInt();
        var coldOnly = place("15.00", new Line(option("Coke 330ml"), 1)).get("orderNumber").asInt();
        var mixed = place("83.00", new Line(option("Chicken Burger"), 1), new Line(option("Cappuccino"), 1));
        bar(mixed.get("id").asLong(), "IN_PROGRESS").andExpect(status().isOk());

        var kitchenEvents = kitchenStream.getResponse().getContentAsString();
        var barEvents = barStream.getResponse().getContentAsString();
        var boardEvents = boardStream.getResponse().getContentAsString();

        assertThat(kitchenEvents).doesNotContain("\"orderNumber\":" + barOnly + ",").doesNotContain("\"orderNumber\":" + coldOnly + ",")
                .contains("\"orderNumber\":" + mixed.get("orderNumber").asInt() + ",").contains("Chicken Burger")
                .doesNotContain("Cappuccino");
        assertThat(barEvents).contains("\"orderNumber\":" + barOnly + ",").doesNotContain("\"orderNumber\":" + coldOnly + ",")
                .doesNotContain("Chicken Burger");
        // The board: the overall status only, never the cold-only order, and no line details.
        assertThat(boardEvents).contains("\"orderNumber\":" + barOnly + ",").doesNotContain("\"orderNumber\":" + coldOnly + ",")
                .contains("\"status\":\"IN_PROGRESS\"").doesNotContain("Cappuccino").doesNotContain("otherStations");
    }
}
