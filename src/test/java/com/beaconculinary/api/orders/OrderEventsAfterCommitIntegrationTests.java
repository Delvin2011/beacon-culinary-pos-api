package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.bar.BarEventBroadcaster;
import com.beaconculinary.api.board.PublicBoardEventBroadcaster;
import com.beaconculinary.api.board.PublicBoardStreamEvent;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.kitchen.KitchenService;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.menu.Station;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O1: the kitchen, bar and public-board streams send only after the transaction commits, so a
 * rolled-back sale or status change never appears on a display. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderEventsAfterCommitIntegrationTests {
    @MockitoSpyBean
    private OrderEventBroadcaster kitchenBroadcaster;
    @MockitoSpyBean
    private BarEventBroadcaster barBroadcaster;
    @MockitoSpyBean
    private PublicBoardEventBroadcaster boardBroadcaster;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderService orderService;
    @Autowired
    private KitchenService kitchenService;
    @Autowired
    private PlatformTransactionManager transactionManager;
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
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 5);
        pos.openShift(cashierToken, "500.00");
        Mockito.clearInvocations(kitchenBroadcaster, barBroadcaster, boardBroadcaster);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    private static void signInAs(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** Runs the work in a transaction that is then rolled back, as a later failure would. */
    private void inRolledBackTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
    }

    private CreateOrderRequest oneMealForCash() {
        var line = new OrderLineRequest();
        line.setDailyMealOptionId(optionId);
        line.setQuantity(1);
        var payment = new OrderPaymentRequest();
        payment.setMethod(PaymentMethod.CASH);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setAmountTendered(new BigDecimal("50.00"));
        var request = new CreateOrderRequest();
        request.setLines(List.of(line));
        request.setPayments(List.of(payment));
        return request;
    }

    private void verifyNothingBroadcast() {
        verify(kitchenBroadcaster, never()).broadcast(any());
        verify(barBroadcaster, never()).broadcast(any());
        verify(boardBroadcaster, never()).broadcast(any());
    }

    @Test
    void aRolledBackSale_isNeverBroadcast() throws Exception {
        signInAs(AuthTestHelper.cashierAId(mockMvc), "CASHIER");

        inRolledBackTransaction(() -> orderService.createOrder(oneMealForCash()));

        verifyNothingBroadcast();
        assertThat(orderRepository.count()).isZero();
        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining()).isEqualTo(5);
    }

    @Test
    void aRolledBackKitchenStatusChange_isNeverBroadcast() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        Mockito.clearInvocations(kitchenBroadcaster, barBroadcaster, boardBroadcaster);
        signInAs(AuthTestHelper.kitchenId(mockMvc), "KITCHEN");

        inRolledBackTransaction(() -> kitchenService.updateStatus(orderId, OrderStatus.IN_PROGRESS));

        verifyNothingBroadcast();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void aCommittedSale_isBroadcastOnceToTheKitchenAndTheBoard_withTheKitchensOwnView() throws Exception {
        pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));

        var kitchenEvent = ArgumentCaptor.forClass(Object.class);
        verify(kitchenBroadcaster, times(1)).broadcast(kitchenEvent.capture());
        var sent = (OrderStatusStreamEvent) kitchenEvent.getValue();
        assertThat(sent.getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
        assertThat(sent.getOrder().getLines()).isNotEmpty().allSatisfy(line -> assertThat(line.getStation()).isEqualTo(Station.KITCHEN));

        var boardEvent = ArgumentCaptor.forClass(Object.class);
        verify(boardBroadcaster, times(1)).broadcast(boardEvent.capture());
        assertThat(((PublicBoardStreamEvent) boardEvent.getValue()).getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
        verify(barBroadcaster, never()).broadcast(any());
    }

    @Test
    void aCommittedKitchenStatusChange_isStillBroadcast() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        Mockito.clearInvocations(kitchenBroadcaster, barBroadcaster, boardBroadcaster);

        pos.patchStatus(orderId, "IN_PROGRESS");

        var kitchenEvent = ArgumentCaptor.forClass(Object.class);
        verify(kitchenBroadcaster, times(1)).broadcast(kitchenEvent.capture());
        assertThat(((OrderStatusStreamEvent) kitchenEvent.getValue()).getEventType()).isEqualTo(OrderEventType.STATUS_CHANGED);
        verify(boardBroadcaster, times(1)).broadcast(any());
    }

    @Test
    void aRefusedSale_isNeverBroadcast() throws Exception {
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("300.00")), new LineReq(optionId, 6, List.of()))
                .andExpect(status().isConflict());

        verifyNothingBroadcast();
    }
}
