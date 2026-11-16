package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O1: with pos.require-client-order-id=true, sales and adjustments without a key are refused. */
@SpringBootTest(properties = "pos.require-client-order-id=true")
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ClientIdRequiredIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
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
    @Autowired
    private MutableClock clock;

    private PosFlowHelper pos;
    private String cashierToken;
    private long optionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 200);
        pos.openShift(cashierToken, "500.00");
    }

    @AfterEach
    void tearDown() {
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
    }

    @Test
    void aSaleWithoutAKey_is400ClientOrderIdRequired() throws Exception {
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIENT_ORDER_ID_REQUIRED"));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void aSaleWithAKey_isCreated_andAnAdjustmentWithoutOne_is400ClientRequestIdRequired() throws Exception {
        var body = "{\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"dailyMealOptionId\":" + optionId
                + ",\"quantity\":1}],\"payments\":[{\"method\":\"CASH\",\"amount\":50,\"amountTendered\":50}]}";
        var orderId = PosFlowHelper.json(mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())).get("id").asLong();

        pos.adjustWith(cashierToken, orderId, "WHOLE_ORDER", null, pos.authorizePin(cashierToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIENT_REQUEST_ID_REQUIRED"));
        assertThat(orderAdjustmentRepository.count()).isZero();
    }
}
