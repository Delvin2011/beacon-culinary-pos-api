package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Stage 2.6 — admin-gated whole-order/extras-only voids and refunds. Reuses Stage 2.1's status
 * enum and status-broadcast infrastructure ({@link OrderStatusEventPublisher}) for the
 * WHOLE_ORDER case; EXTRAS_ONLY instead pushes directly onto the kitchen's own SSE channel
 * ({@link OrderEventBroadcaster}) so the public board — which never subscribes to that
 * broadcaster — has no way to react to a change its payload wouldn't reflect anyway.
 *
 * Stage 3.2 adds a DISCOUNT branch on the same endpoint: money handed back on an already-paid
 * order, categorized separately from VOID/REFUND for reporting, with no lifecycle transition.
 *
 * Stage 3.3 lets either Stage 2.6's single-use authorizationToken or a longer-lived, reusable
 * management sessionToken authorize this call.
 */
@Service
@AllArgsConstructor
public class OrderAdjustmentService {
    private final OrderRepository orderRepository;
    private final ShiftRepository shiftRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AuthService authService;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final OrderMapper orderMapper;
    private final OrderSummaryMapper orderSummaryMapper;
    private final OrderStatusEventPublisher orderStatusEventPublisher;
    private final OrderEventBroadcaster orderEventBroadcaster;
    private final Clock clock;

    @Transactional
    public OrderDto adjustOrder(Long orderId, CreateOrderAdjustmentRequest request) {
        // Step 1: validate the authorization first, so any failure below still leaves a
        // single-use token unusable — see AdminAuthorizationService#consumeToken. A sessionToken
        // is validated but never consumed.
        var admin = resolveAuthorization(request);

        if (request.getReasonCode() == OrderAdjustmentReasonCode.OTHER
                && (request.getNote() == null || request.getNote().isBlank())) {
            throw new InvalidOrderRequestException("note is required when reasonCode is OTHER.");
        }

        // Step 2: load the order and enforce same-day scope.
        var order = orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new);
        if (!order.getOrderDate().equals(LocalDate.now(clock))) {
            throw new InvalidOrderRequestException("Adjustments are only allowed on today's orders.");
        }

        // Step 3: terminal orders have nothing left to adjust — applies to discounts too.
        if (order.getStatus() == OrderStatus.VOIDED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new OrderAlreadyAdjustedException();
        }

        // Stage 2.5: attribute the cash-out to whichever shift is open right now — may be a
        // different, later shift than order.shift when the sale and the adjustment happen in
        // different shifts. Requires an open shift the same way placing an order does.
        var openShift = shiftRepository.findFirstByStatus(ShiftStatus.OPEN)
                .orElseThrow(NoOpenShiftException::new);

        var cashier = authService.getCurrentUser();

        var adjustment = new OrderAdjustment();
        adjustment.setOrder(order);
        adjustment.setShift(openShift);
        adjustment.setReasonCode(request.getReasonCode());
        adjustment.setNote(request.getNote());
        adjustment.setRequestedBy(cashier);
        adjustment.setAuthorizedBy(admin);

        if (request.getRequestedAction() == OrderAdjustmentRequestedAction.DISCOUNT) {
            applyDiscount(order, request, adjustment);
        } else {
            if (request.getScope() == null) {
                throw new InvalidOrderRequestException("scope is required.");
            }
            var action = order.getStatus() == OrderStatus.PENDING ? OrderAdjustmentAction.VOID : OrderAdjustmentAction.REFUND;
            var amount = request.getScope() == OrderAdjustmentScope.WHOLE_ORDER
                    ? adjustWholeOrder(order, action)
                    : adjustExtrasOnly(order, action);

            adjustment.setScope(request.getScope());
            adjustment.setAction(action);
            adjustment.setAmount(amount);
        }

        // Stage 4 Part C — every VOID/REFUND/DISCOUNT pays out as cash or an account-balance
        // credit, computed automatically from whether the order's payment included ACCOUNT
        // (mutually exclusive with CASH/CARD, so this is a clean binary — no allocation math).
        var accountPayment = order.getPayments().stream()
                .filter(payment -> payment.getMethod() == PaymentMethod.ACCOUNT)
                .findFirst();
        if (accountPayment.isPresent()) {
            adjustment.setRefundMethod(RefundMethod.ACCOUNT_BALANCE);
            adjustment.setAccount(accountPayment.get().getAccount());
        } else {
            adjustment.setRefundMethod(RefundMethod.CASH);
        }

        order.getAdjustments().add(adjustment);
        orderRepository.save(order);
        return orderMapper.toDto(order);
    }

    private User resolveAuthorization(CreateOrderAdjustmentRequest request) {
        if (request.getSessionToken() != null && !request.getSessionToken().isBlank()) {
            return adminAuthorizationService.validateSessionToken(request.getSessionToken());
        }
        return adminAuthorizationService.consumeToken(request.getAuthorizationToken());
    }

    // Step 4 (Stage 2.6).
    private BigDecimal adjustWholeOrder(Order order, OrderAdjustmentAction action) {
        var amount = order.getTotal();

        if (action == OrderAdjustmentAction.VOID) {
            for (var line : order.getLines()) {
                dailyMealOptionRepository.incrementPortionsRemaining(line.getDailyMealOption().getId(), line.getQuantity());
                for (var extra : line.getExtras()) {
                    if (!extra.isAdjusted()) {
                        dailyComponentStockRepository.incrementBufferRemaining(
                                extra.getDailyComponentStock().getId(), extra.getQuantity());
                    }
                }
            }
        }

        var fromStatus = order.getStatus();
        order.setTotal(BigDecimal.ZERO);
        order.setStatus(action == OrderAdjustmentAction.VOID ? OrderStatus.VOIDED : OrderStatus.REFUNDED);
        orderRepository.save(order);
        // Kitchen and public boards both filter to non-terminal statuses, so this single
        // broadcast is enough to make the order disappear from both.
        orderStatusEventPublisher.publish(order, fromStatus);

        return amount;
    }

    // Step 5 (Stage 2.6).
    private BigDecimal adjustExtrasOnly(Order order, OrderAdjustmentAction action) {
        // Checked ahead of the "no unadjusted extras" case below so a genuine repeat attempt
        // (order.extrasAdjusted already true) reads as a 409 conflict rather than a 400 — the
        // per-extra adjusted flags would look identical either way, but this order-level flag is
        // specifically what distinguishes "already adjusted" from "never had extras at all".
        if (order.isExtrasAdjusted()) {
            throw new ExtrasAlreadyAdjustedException();
        }

        List<OrderLineExtra> unadjustedExtras = order.getLines().stream()
                .flatMap(line -> line.getExtras().stream())
                .filter(extra -> !extra.isAdjusted())
                .toList();

        if (unadjustedExtras.isEmpty()) {
            throw new InvalidOrderRequestException("Order has no unadjusted extras.");
        }

        // Guards the true race: two concurrent requests both passing the check above.
        if (orderRepository.markExtrasAdjusted(order.getId()) == 0) {
            throw new ExtrasAlreadyAdjustedException();
        }
        order.setExtrasAdjusted(true);

        var amount = unadjustedExtras.stream()
                .map(OrderLineExtra::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (action == OrderAdjustmentAction.VOID) {
            for (var extra : unadjustedExtras) {
                dailyComponentStockRepository.incrementBufferRemaining(
                        extra.getDailyComponentStock().getId(), extra.getQuantity());
            }
        }
        unadjustedExtras.forEach(extra -> extra.setAdjusted(true));

        order.setTotal(order.getTotal().subtract(amount));
        orderRepository.save(order);

        if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.IN_PROGRESS) {
            orderEventBroadcaster.broadcast(new OrderStatusStreamEvent(OrderEventType.ORDER_UPDATED, orderSummaryMapper.toDto(order)));
        }

        return amount;
    }

    /**
     * Stage 3.2 — money handed back on an already-paid order, categorized separately from
     * VOID/REFUND. Whole-order only; no lifecycle transition; no stock change; no SSE broadcast,
     * since nothing on the kitchen/public side changes.
     */
    private void applyDiscount(Order order, CreateOrderAdjustmentRequest request, OrderAdjustment adjustment) {
        var discountType = request.getDiscountType();
        var discountValue = request.getDiscountValue();
        if (discountType == null) {
            throw new InvalidOrderRequestException("discountType is required.");
        }
        if (discountValue == null) {
            throw new InvalidOrderRequestException("discountValue is required.");
        }

        BigDecimal amount;
        if (discountType == DiscountType.PERCENTAGE) {
            if (discountValue.compareTo(BigDecimal.ZERO) <= 0 || discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new InvalidOrderRequestException("discountValue must be in (0, 100] for a PERCENTAGE discount.");
            }
            amount = order.getTotal().multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            if (discountValue.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidOrderRequestException("discountValue must be positive for a FIXED_AMOUNT discount.");
            }
            amount = discountValue;
        }

        if (amount.compareTo(order.getTotal()) > 0) {
            throw new InvalidOrderRequestException("Discount amount cannot exceed the order's current total.");
        }

        order.setTotal(order.getTotal().subtract(amount));

        adjustment.setScope(OrderAdjustmentScope.WHOLE_ORDER);
        adjustment.setAction(OrderAdjustmentAction.DISCOUNT);
        adjustment.setDiscountType(discountType);
        adjustment.setDiscountValue(discountValue);
        adjustment.setAmount(amount);
    }
}
