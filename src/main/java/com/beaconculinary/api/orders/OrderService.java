package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

@Service
@AllArgsConstructor
public class OrderService {
    private final ShiftRepository shiftRepository;
    private final AuthService authService;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final OrderMapper orderMapper;
    private final OrderStatusEventPublisher orderStatusEventPublisher;
    private final Clock clock;

    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(NoOpenShiftException::new);

        var today = LocalDate.now(clock);
        var now = LocalTime.now(clock);

        var order = new Order();
        order.setShift(shift);
        order.setCashier(currentUser);
        order.setOrderDate(today);

        var subtotal = BigDecimal.ZERO;
        var total = BigDecimal.ZERO;

        // Phase 1: validate and price every line/extra before writing anything, so an invalid
        // line anywhere in the request never triggers a partial decrement.
        for (var lineRequest : request.getLines()) {
            var option = dailyMealOptionRepository.findById(lineRequest.getDailyMealOptionId())
                    .orElseThrow(() -> new InvalidOrderRequestException("dailyMealOptionId does not exist."));

            if (!option.getOptionDate().equals(today)) {
                throw new InvalidOrderRequestException("Meal option is not available today.");
            }
            if (!option.getMealPeriod().isActiveAt(now)) {
                throw new InvalidOrderRequestException("Meal option's meal period is not currently active.");
            }

            var line = new OrderLine();
            line.setOrder(order);
            line.setDailyMealOption(option);
            line.setUnitPrice(option.getPrice());
            line.setQuantity(lineRequest.getQuantity());
            var lineTotal = option.getPrice().multiply(BigDecimal.valueOf(lineRequest.getQuantity()));
            line.setLineTotal(lineTotal);
            subtotal = subtotal.add(lineTotal);
            total = total.add(lineTotal);

            for (var extraRequest : lineRequest.getExtras()) {
                var stock = dailyComponentStockRepository.findById(extraRequest.getDailyComponentStockId())
                        .orElseThrow(() -> new InvalidOrderRequestException("dailyComponentStockId does not exist."));

                if (!stock.getOptionDate().equals(today)) {
                    throw new InvalidOrderRequestException("Extra component has no stock declared for today.");
                }
                // Cross-dish extras are fine — the only requirement is the same meal period as
                // the line it's attached to, not the same dish composition.
                if (!stock.getMealPeriod().getId().equals(option.getMealPeriod().getId())) {
                    throw new InvalidOrderRequestException("Extra component is not stocked for this meal period.");
                }

                var extra = new OrderLineExtra();
                extra.setOrderLine(line);
                extra.setDailyComponentStock(stock);
                extra.setPriceDelta(stock.getExtraPrice());
                extra.setQuantity(extraRequest.getQuantity());
                var extraLineTotal = stock.getExtraPrice().multiply(BigDecimal.valueOf(extraRequest.getQuantity()));
                extra.setLineTotal(extraLineTotal);
                total = total.add(extraLineTotal);

                line.getExtras().add(extra);
            }

            order.getLines().add(line);
        }

        // Phase 2: atomic, conditional decrement per line/extra. A failed decrement throws,
        // which rolls back the whole transaction — including any decrements already applied
        // earlier in this same request — so no order is ever left half-fulfilled.
        for (var line : order.getLines()) {
            var decremented = dailyMealOptionRepository.decrementPortionsRemaining(
                    line.getDailyMealOption().getId(), line.getQuantity());
            if (decremented == 0) {
                throw new InsufficientStockException(
                        "Not enough portions remaining for '" + line.getDailyMealOption().getName() + "'.");
            }

            for (var extra : line.getExtras()) {
                var decrementedExtra = dailyComponentStockRepository.decrementBufferRemaining(
                        extra.getDailyComponentStock().getId(), extra.getQuantity());
                if (decrementedExtra == 0) {
                    throw new InsufficientStockException("Not enough stock remaining for the requested extra.");
                }
            }
        }

        attachPayments(order, request.getPayments(), total);

        var orderNumber = orderRepository.findMaxOrderNumberForDate(today) + 1;
        order.setOrderNumber(orderNumber);
        order.setSubtotal(subtotal);
        order.setTotal(total);
        order.setOriginalTotal(total);

        orderRepository.save(order);
        // The implicit null -> PENDING transition — retrofits this order onto the KDS stream
        // the instant it's paid, with no polling needed on the kitchen side.
        orderStatusEventPublisher.publish(order, null);

        return orderMapper.toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getTodayOrders() {
        var today = LocalDate.now(clock);
        return orderRepository.findByOrderDateOrderByOrderNumberDesc(today)
                .stream().map(orderMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long id) {
        var order = orderRepository.findById(id).orElseThrow(OrderNotFoundException::new);
        return orderMapper.toDto(order);
    }

    @Transactional
    public OrderDto markPrintFailed(Long id) {
        var order = orderRepository.findById(id).orElseThrow(OrderNotFoundException::new);
        order.setPrintFailed(true);
        orderRepository.save(order);
        return orderMapper.toDto(order);
    }

    /**
     * Stage 4 Part A/B — validates the payment split (1-2 entries, at most one per method,
     * ACCOUNT mutually exclusive with everything else, amounts summing exactly to the order
     * total) and builds one {@link OrderPayment} row per entry. Runs after the stock decrement,
     * matching where the old single-method check used to live — safe because the whole method
     * is one transaction, so a rejected payment split still rolls back any decrements already
     * applied.
     */
    private void attachPayments(Order order, List<OrderPaymentRequest> paymentRequests, BigDecimal total) {
        if (paymentRequests.size() > 2) {
            throw new InvalidOrderRequestException("A maximum of 2 payment entries is allowed.");
        }

        var methods = EnumSet.noneOf(PaymentMethod.class);
        for (var paymentRequest : paymentRequests) {
            if (!methods.add(paymentRequest.getMethod())) {
                throw new InvalidOrderRequestException("Each payment method may appear at most once.");
            }
        }
        if (methods.contains(PaymentMethod.ACCOUNT) && paymentRequests.size() > 1) {
            throw new InvalidOrderRequestException("ACCOUNT payment cannot be combined with any other payment method.");
        }

        var sum = paymentRequests.stream().map(OrderPaymentRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) != 0) {
            throw new InvalidOrderRequestException("Sum of payments must equal the order total.");
        }

        for (var paymentRequest : paymentRequests) {
            var payment = new OrderPayment();
            payment.setOrder(order);
            payment.setMethod(paymentRequest.getMethod());
            payment.setAmount(paymentRequest.getAmount());

            switch (paymentRequest.getMethod()) {
                case CASH -> {
                    if (paymentRequest.getAmountTendered() == null) {
                        throw new InvalidOrderRequestException("amountTendered is required for a CASH payment.");
                    }
                    if (paymentRequest.getAmountTendered().compareTo(paymentRequest.getAmount()) < 0) {
                        throw new InvalidOrderRequestException("amountTendered is less than the CASH payment amount.");
                    }
                    payment.setAmountTendered(paymentRequest.getAmountTendered());
                    payment.setChangeDue(paymentRequest.getAmountTendered().subtract(paymentRequest.getAmount()));
                }
                case CARD -> {
                    if (paymentRequest.getCardReference() == null || paymentRequest.getCardReference().isBlank()) {
                        throw new InvalidOrderRequestException("cardReference is required for a CARD payment.");
                    }
                    payment.setCardReference(paymentRequest.getCardReference());
                }
                case ACCOUNT -> {
                    if (paymentRequest.getAccountId() == null) {
                        throw new InvalidOrderRequestException("accountId is required for an ACCOUNT payment.");
                    }
                    var account = accountRepository.findById(paymentRequest.getAccountId())
                            .orElseThrow(() -> new InvalidOrderRequestException("accountId does not exist."));
                    if (!account.isActive()) {
                        throw new InvalidOrderRequestException("Account is not active.");
                    }
                    payment.setAccount(account);
                }
            }

            order.getPayments().add(payment);
        }
    }
}
