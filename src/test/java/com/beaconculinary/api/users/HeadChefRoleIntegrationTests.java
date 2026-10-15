package com.beaconculinary.api.users;

import com.beaconculinary.api.inventory.IngredientRepository;
import com.beaconculinary.api.inventory.IngredientStockMovementRepository;
import com.beaconculinary.api.inventory.StockRequestRepository;
import com.beaconculinary.api.inventory.StockTakeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Head Chef role: what it may and may not do. Plans daily menus, views the catalog and ingredient
 * stock, requests Issue/Waste stock and submits stock takes (editing or cancelling only its own
 * pending items), uses the kitchen display, and reviews Sales Summary. It never approves, never
 * authorizes as a manager, never raises purchase-order requests, and has no POS, cash or GRV access.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HeadChefRoleIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StockRequestRepository stockRequestRepository;
    @Autowired
    private StockTakeRepository stockTakeRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;

    private String headChef;
    private String admin;
    private String stockAdmin;
    private String stockClerk;
    private final List<Long> ingredientIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        headChef = AuthTestHelper.loginAsHeadChef(mockMvc);
        admin = AuthTestHelper.loginAsAdmin(mockMvc);
        stockAdmin = AuthTestHelper.loginAsStockAdmin(mockMvc);
        stockClerk = AuthTestHelper.loginAsStockClerk(mockMvc);
    }

    @AfterEach
    void tearDown() {
        stockTakeRepository.deleteAll();
        stockRequestRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        for (var id : ingredientIds) {
            ingredientStockMovementRepository.findAll().stream()
                    .filter(m -> id.equals(m.getIngredient().getId()))
                    .forEach(ingredientStockMovementRepository::delete);
            ingredientRepository.deleteById(id);
        }
        ingredientIds.clear();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
        return as(token, request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private long id(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long ingredient(String name) throws Exception {
        var categories = MAPPER.readTree(as(admin, get("/count-sheet-categories")).andReturn().getResponse().getContentAsString());
        long categoryId = 0;
        for (var node : categories) {
            if (node.get("name").asText().equals("DRYSTOCK")) {
                categoryId = node.get("id").asLong();
            }
        }
        var id = id(as(admin, post("/admin/ingredients"),
                "{\"name\":\"" + name + "\",\"unit\":\"KG\",\"countSheetCategoryId\":" + categoryId + "}"));
        ingredientIds.add(id);
        return id;
    }

    private long locationId(String name) throws Exception {
        for (var node : MAPPER.readTree(as(admin, get("/locations")).andReturn().getResponse().getContentAsString())) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " not seeded");
    }

    private static String stockRequest(String type, long ingredientId, String quantity, Long locationId) {
        return "{\"requestType\":\"" + type + "\"" + (locationId == null ? "" : ",\"locationId\":" + locationId)
                + ",\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantity\":" + quantity + ",\"reason\":\"Spoiled\"}]}";
    }

    private static String stockTake(long locationId, long ingredientId, String actual) {
        return "{\"locationId\":" + locationId + ",\"lines\":[{\"ingredientId\":" + ingredientId + ",\"actualQuantity\":" + actual + "}]}";
    }

    @Test
    void menuCatalogIsViewOnly_andDailyPlanningIsFull() throws Exception {
        var componentId = id(as(admin, post("/admin/component-catalog"), "{\"name\":\"HC Beef\",\"extraPrice\":15.00}"));
        var mealId = id(as(admin, post("/admin/meal-catalog"),
                "{\"name\":\"HC Pap & Beef\",\"description\":\"desc\",\"price\":50.00,\"componentIds\":[" + componentId + "]}"));
        long lunchId = 0;
        for (var node : MAPPER.readTree(mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString())) {
            if (node.get("name").asText().equalsIgnoreCase("lunch")) {
                lunchId = node.get("id").asLong();
            }
        }
        var today = LocalDate.now().toString();

        as(headChef, get("/admin/meal-catalog")).andExpect(status().isOk());
        as(headChef, get("/admin/component-catalog")).andExpect(status().isOk());
        as(headChef, post("/admin/meal-catalog"), "{\"name\":\"Nope\",\"description\":\"d\",\"price\":1,\"componentIds\":[]}")
                .andExpect(status().isForbidden());
        as(headChef, post("/admin/component-catalog"), "{\"name\":\"Nope\",\"extraPrice\":1}")
                .andExpect(status().isForbidden());

        // Daily planning: create, view, and the confirm step are all open to the Head Chef.
        as(headChef, post("/admin/daily-options"), "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today
                + "\",\"mealCatalogId\":" + mealId + ",\"plannedPortions\":20}").andExpect(status().isCreated());
        as(headChef, get("/admin/daily-planning/" + today + "?period=lunch")).andExpect(status().isOk());
        assertThat(statusOf(as(headChef, get("/admin/daily-planning/" + today + "/ingredient-requirements")))).isNotIn(401, 403);
        assertThat(statusOf(as(headChef, post("/admin/daily-planning/" + today + "/confirm-ingredient-requirements"), "{}")))
                .isNotIn(401, 403);
    }

    @Test
    void ingredientsAreViewOnly() throws Exception {
        var ingredientId = ingredient("HC Rice");
        as(headChef, get("/admin/ingredients")).andExpect(status().isOk());
        as(headChef, get("/admin/ingredients/" + ingredientId + "/stock")).andExpect(status().isOk());
        as(headChef, get("/count-sheet-categories")).andExpect(status().isOk());
        as(headChef, post("/admin/ingredients"), "{\"name\":\"Nope\",\"unit\":\"KG\",\"countSheetCategoryId\":1}")
                .andExpect(status().isForbidden());
        as(headChef, put("/admin/ingredients/" + ingredientId),
                "{\"name\":\"Nope\",\"unit\":\"KG\",\"countSheetCategoryId\":1,\"active\":true}")
                .andExpect(status().isForbidden());
    }

    @Test
    void salesSummaryOnly_amongTheReports() throws Exception {
        as(headChef, get("/admin/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", contains("sales-summary")));
        as(headChef, get("/admin/reports/sales-summary")).andExpect(status().isOk());
        as(headChef, get("/admin/reports/tender-summary")).andExpect(status().isForbidden());
        as(headChef, get("/admin/reports/cash-up-variance")).andExpect(status().isForbidden());
        as(headChef, get("/admin/reports/tender-summary/export?format=csv")).andExpect(status().isForbidden());
    }

    @Test
    void kitchenDisplay_sameRightsAsKitchen_andPinLoginWorks() throws Exception {
        as(headChef, get("/kitchen/orders")).andExpect(status().isOk());

        var headChefId = AuthTestHelper.userId(mockMvc, AuthTestHelper.HEAD_CHEF_EMAIL);
        var pinToken = AuthTestHelper.loginWithPin(mockMvc, headChefId, "654321");
        as(pinToken, get("/kitchen/orders")).andExpect(status().isOk());
    }

    @Test
    void grvAndWasteHistory_areReadOnly() throws Exception {
        as(headChef, get("/admin/grv")).andExpect(status().isOk());
        as(headChef, get("/admin/waste")).andExpect(status().isOk());
        // No capturing, editing or uploading GRVs, and no direct waste posting.
        as(headChef, post("/admin/grv"), "{}").andExpect(status().isForbidden());
        as(headChef, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/grv/1"), "{}")
                .andExpect(status().isForbidden());
        as(headChef, post("/admin/grv/bulk-import")).andExpect(status().isForbidden());
        as(headChef, post("/admin/waste"), "{}").andExpect(status().isForbidden());
    }

    @Test
    void neverPosCashPurchaseOrdersApprovalsOrManagerAuthority() throws Exception {
        for (var request : List.of(get("/admin/purchase-orders"), get("/admin/shifts"),
                get("/admin/settings/vat"), get("/orders/today"), get("/shifts/open"), get("/admin/recipes"))) {
            as(headChef, request).andExpect(status().isForbidden());
        }
        // Can't request manager authorization, and the Head Chef's PIN authorizes nothing.
        as(headChef, post("/admin/authorize"), "{\"pin\":\"654321\"}").andExpect(status().isForbidden());
        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        assertThat(statusOf(as(cashier, post("/admin/authorize"), "{\"pin\":\"000000\"}"))).isEqualTo(401);
    }

    @Test
    void stockRequests_issueAndWasteOnly_seesEveryone_editsOnlyOwnPending_neverApproves() throws Exception {
        var ingredientId = ingredient("HC Flour");
        var kitchenId = locationId("Kitchen");

        var issue = id(as(headChef, post("/stock-requests"), stockRequest("ISSUE", ingredientId, "5", null)));
        var waste = id(as(headChef, post("/stock-requests"), stockRequest("WASTE", ingredientId, "1", kitchenId)));
        as(headChef, post("/stock-requests"), stockRequest("ORDER", ingredientId, "5", null)).andExpect(status().isForbidden());

        // Sees a stock clerk's request too, read-only.
        var clerks = id(as(stockClerk, post("/stock-requests"), stockRequest("ISSUE", ingredientId, "2", null)));
        as(headChef, get("/stock-requests")).andExpect(jsonPath("$[*].id", hasItem((int) clerks)));
        as(headChef, get("/stock-requests/" + clerks)).andExpect(status().isOk());
        as(headChef, put("/stock-requests/" + clerks), stockRequest("ISSUE", ingredientId, "9", null)).andExpect(status().isForbidden());
        as(headChef, post("/stock-requests/" + clerks + "/cancel")).andExpect(status().isForbidden());

        // Edits and cancels its own while pending.
        as(headChef, put("/stock-requests/" + issue), stockRequest("ISSUE", ingredientId, "7", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].requestedQuantity").value(7.0));
        as(headChef, put("/stock-requests/" + issue), stockRequest("WASTE", ingredientId, "7", kitchenId))
                .andExpect(status().isBadRequest());
        as(headChef, post("/stock-requests/" + waste + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        as(headChef, put("/stock-requests/" + waste), stockRequest("WASTE", ingredientId, "2", kitchenId)).andExpect(status().isConflict());

        // Approval stays with the stock admin; a cancelled request can't be actioned.
        as(headChef, post("/stock-requests/" + issue + "/action"), "{\"lines\":[]}").andExpect(status().isForbidden());
        as(stockAdmin, post("/stock-requests/" + waste + "/action"),
                "{\"lines\":[{\"ingredientId\":" + ingredientId + ",\"actionedQuantity\":1}]}").andExpect(status().isConflict());
    }

    @Test
    void stockTakes_submitSeeEveryoneEditOrCancelOwnPending_neverReview() throws Exception {
        var ingredientId = ingredient("HC Oil");
        var kitchenId = locationId("Kitchen");

        var mine = id(as(headChef, post("/stock-takes"), stockTake(kitchenId, ingredientId, "3")));
        var clerks = id(as(stockClerk, post("/stock-takes"), stockTake(kitchenId, ingredientId, "4")));

        as(headChef, get("/stock-takes")).andExpect(jsonPath("$[*].id", hasItem((int) clerks)));
        as(headChef, put("/stock-takes/" + clerks), stockTake(kitchenId, ingredientId, "9")).andExpect(status().isForbidden());

        as(headChef, put("/stock-takes/" + mine), stockTake(kitchenId, ingredientId, "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].actualQuantity").value(5.0));
        as(headChef, post("/stock-takes/" + mine + "/review"), "{\"decision\":\"APPROVE\"}").andExpect(status().isForbidden());
        as(headChef, post("/stock-takes/" + mine + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        as(stockAdmin, post("/stock-takes/" + mine + "/review"), "{\"decision\":\"APPROVE\"}").andExpect(status().isConflict());
        as(headChef, post("/stock-takes/" + mine + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void stockClerk_canAlsoEditAndCancelOwnPendingItems() throws Exception {
        var ingredientId = ingredient("HC Salt");
        var request = id(as(stockClerk, post("/stock-requests"), stockRequest("ISSUE", ingredientId, "2", null)));

        as(stockClerk, put("/stock-requests/" + request), stockRequest("ISSUE", ingredientId, "3", null)).andExpect(status().isOk());
        as(stockClerk, post("/stock-requests/" + request + "/cancel")).andExpect(jsonPath("$.status").value("CANCELLED"));
        // An approver can't edit or cancel someone else's request either.
        var another = id(as(stockClerk, post("/stock-requests"), stockRequest("ISSUE", ingredientId, "2", null)));
        as(stockAdmin, post("/stock-requests/" + another + "/cancel")).andExpect(status().isForbidden());
    }

    @Test
    void loginTiles_perScreen() throws Exception {
        mockMvc.perform(get("/users/login-tiles?screen=KITCHEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Head Chef")))
                .andExpect(jsonPath("$[*].name", hasItem("Kitchen Station")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Canteen Cashier"))));
        mockMvc.perform(get("/users/login-tiles?screen=POS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Canteen Cashier")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Head Chef"))));
        mockMvc.perform(get("/users/cashiers"))
                .andExpect(jsonPath("$[*].name", not(hasItem("Head Chef"))));
        mockMvc.perform(get("/users/login-tiles?screen=BACK_OFFICE")).andExpect(status().isBadRequest());
    }
}
