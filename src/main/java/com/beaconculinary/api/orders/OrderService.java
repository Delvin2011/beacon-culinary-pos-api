package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.DailyPlanItemStatus;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final AdminAuthorizationService adminAuthorizationService;
    private final Clock clock;

    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        var currentUser = authService.getCurrentUser();
        // POS Oversight B1: sell on the till's single open shift (the drawer), which the caller
        // must own unless they're an ADMIN; cashier_id below still records who rang it up. The
        // shared lock serialises this against a concurrent close (B3).
        var shift = shiftRepository.findOpenShiftWithSharedLock().orElseThrow(NoOpenShiftException::new);
        var owner = shift.getCashier();
        if (!owner.getId().equals(currentUser.getId()) && currentUser.getRole() != Role.ADMIN) {
            throw new TillInUseException(owner.getName());
        }

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
            // /menu/today already hides these, but a stale POS screen or a direct API call can
            // still name one. Status only moves forward, so this check can't go stale mid-order.
            if (option.getStatus() != DailyPlanItemStatus.READY) {
                throw new InvalidOrderRequestException("Meal option '" + option.getName() + "' is not ready for sale yet.");
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
                if (stock.getStatus() != DailyPlanItemStatus.READY) {
                    throw new InvalidOrderRequestException("Extra component is not ready for sale yet.");
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

        // Discount at sale: total so far is the pre-discount total (meals + extras). A discount
        // lowers what the customer pays; payments are then validated against the discounted
        // figure. Any authorization token is consumed inside this transaction, so if the order
        // fails below it stays valid for the retry.
        var preDiscountTotal = total;
        // A complimentary order is given away whole: no discount, no payment, and its list value
        // stays in preDiscountTotal while everything that would have been paid is 0. Its token is
        // consumed in this transaction exactly like a discount's.
        var complimentary = request.getComplimentary() == null ? null : buildComplimentary(request, currentUser);
        var discount = complimentary != null || request.getDiscount() == null
                ? null
                : buildDiscount(request.getDiscount(), preDiscountTotal, currentUser);
        var saleDiscount = discount == null ? BigDecimal.ZERO : discount.getAmount();
        var originalTotal = complimentary != null ? BigDecimal.ZERO : preDiscountTotal.subtract(saleDiscount);

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

        if (complimentary == null) {
            attachPayments(order, request.getPayments(), originalTotal);
        }

        var orderNumber = orderRepository.findMaxOrderNumberForDate(today) + 1;
        order.setOrderNumber(orderNumber);
        order.setOrderKind(complimentary == null ? OrderKind.SALE : OrderKind.COMPLIMENTARY);
        order.setSubtotal(subtotal);
        order.setPreDiscountTotal(preDiscountTotal);
        order.setSaleDiscount(saleDiscount);
        order.setOriginalTotal(originalTotal);
        order.setTotal(originalTotal);
        if (discount != null) {
            discount.setOrder(order);
            order.setDiscount(discount);
        }
        if (complimentary != null) {
            complimentary.setOrder(order);
            order.setComplimentary(complimentary);
        }

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
     * Validates a discount at sale and works out its amount. Order of checks: the discount's own
     * fields (400 DISCOUNT_INVALID), its size against the pre-discount total (400
     * DISCOUNT_TOO_LARGE), and only then the manager's authorization (401 AUTHORIZATION_INVALID),
     * so a rejected discount never uses up a PIN token. A discount can never make an order free.
     */
    private OrderDiscount buildDiscount(SaleDiscountRequest request, BigDecimal preDiscountTotal, User requestedBy) {
        var type = parseEnum(DiscountType.class, request.getType(), "type must be PERCENTAGE or FIXED_AMOUNT.");
        var reasonCode = parseEnum(SaleDiscountReasonCode.class, request.getReasonCode(),
                "reasonCode must be one of STAFF, PROMOTION, MANAGER_GOODWILL, PRICING_CORRECTION, OTHER.");
        var value = request.getValue();
        if (value == null) {
            throw OrderRuleException.discountInvalid("value is required.");
        }
        if (reasonCode == SaleDiscountReasonCode.OTHER && (request.getNote() == null || request.getNote().isBlank())) {
            throw OrderRuleException.discountInvalid("note is required when reasonCode is OTHER.");
        }

        BigDecimal amount;
        if (type == DiscountType.PERCENTAGE) {
            if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw OrderRuleException.discountInvalid("A percentage discount must be above 0 and below 100.");
            }
            amount = preDiscountTotal.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            if (value.signum() <= 0) {
                throw OrderRuleException.discountInvalid("A fixed discount must be above 0.");
            }
            amount = value.setScale(2, RoundingMode.HALF_UP);
        }
        if (amount.signum() <= 0) {
            throw OrderRuleException.discountTooLarge("The discount works out to R0.00.");
        }
        if (amount.compareTo(preDiscountTotal) >= 0) {
            throw OrderRuleException.discountTooLarge("A discount must be less than the order total.");
        }

        var authorization = request.getAuthorization();
        var authorizationToken = authorization == null ? null : blankToNull(authorization.getAuthorizationToken());
        var sessionToken = authorization == null ? null : blankToNull(authorization.getSessionToken());
        if ((authorizationToken == null) == (sessionToken == null)) {
            throw OrderRuleException.discountInvalid("authorization needs exactly one of authorizationToken or sessionToken.");
        }
        var admin = authorizationToken != null
                ? adminAuthorizationService.consumeTokenInCurrentTransaction(authorizationToken)
                : adminAuthorizationService.validateSessionTokenForDiscount(sessionToken);

        var discount = new OrderDiscount();
        discount.setDiscountType(type);
        discount.setDiscountValue(value);
        discount.setAmount(amount);
        discount.setReasonCode(reasonCode);
        discount.setNote(blankToNull(request.getNote()));
        discount.setRequestedBy(requestedBy);
        discount.setAuthorizedBy(admin);
        return discount;
    }

    /**
     * Validates a complimentary order's request: no discount, no payments, a known reason (OTHER
     * needs a note) and exactly one token, all as 400 COMPLIMENTARY_INVALID; only then the
     * manager's authorization (401 AUTHORIZATION_INVALID), so a rejected request never uses up a
     * PIN token. Same token rules as a discount: a single-use token is consumed in this
     * transaction, a session token never is, and the approver must be an active ADMIN.
     */
    private OrderComplimentary buildComplimentary(CreateOrderRequest request, User requestedBy) {
        var complimentaryRequest = request.getComplimentary();
        if (request.getDiscount() != null) {
            throw OrderRuleException.complimentaryInvalid("A complimentary order can't also have a discount.");
        }
        if (request.getPayments() != null && !request.getPayments().isEmpty()) {
            throw OrderRuleException.complimentaryInvalid("A complimentary order takes no payments.");
        }
        ComplimentaryReasonCode reasonCode;
        try {
            reasonCode = ComplimentaryReasonCode.valueOf(
                    complimentaryRequest.getReasonCode() == null ? "" : complimentaryRequest.getReasonCode().trim());
        } catch (IllegalArgumentException e) {
            throw OrderRuleException.complimentaryInvalid(
                    "reasonCode must be one of STAFF_MEAL, MANAGEMENT, GUEST, TASTING, OTHER.");
        }
        if (reasonCode == ComplimentaryReasonCode.OTHER && blankToNull(complimentaryRequest.getNote()) == null) {
            throw OrderRuleException.complimentaryInvalid("note is required when reasonCode is OTHER.");
        }

        var authorization = complimentaryRequest.getAuthorization();
        var authorizationToken = authorization == null ? null : blankToNull(authorization.getAuthorizationToken());
        var sessionToken = authorization == null ? null : blankToNull(authorization.getSessionToken());
        if ((authorizationToken == null) == (sessionToken == null)) {
            throw OrderRuleException.complimentaryInvalid("authorization needs exactly one of authorizationToken or sessionToken.");
        }
        var admin = authorizationToken != null
                ? adminAuthorizationService.consumeTokenInCurrentTransaction(authorizationToken)
                : adminAuthorizationService.validateSessionTokenForDiscount(sessionToken);

        var complimentary = new OrderComplimentary();
        complimentary.setReasonCode(reasonCode);
        complimentary.setNote(blankToNull(complimentaryRequest.getNote()));
        complimentary.setRequestedBy(requestedBy);
        complimentary.setAuthorizedBy(admin);
        return complimentary;
    }

    /**
     * Charging an order to an account needs an admin's approval, with the same token rules as a
     * discount. It runs last for an ACCOUNT payment, after the split, the total and the account
     * itself are checked, so a rejected payment never uses up a PIN token (400
     * ACCOUNT_PAYMENT_INVALID if the token is missing; 401 AUTHORIZATION_INVALID if it's bad).
     */
    private User authorizeAccountPayment(SaleDiscountRequest.Authorization authorization) {
        var authorizationToken = authorization == null ? null : blankToNull(authorization.getAuthorizationToken());
        var sessionToken = authorization == null ? null : blankToNull(authorization.getSessionToken());
        if ((authorizationToken == null) == (sessionToken == null)) {
            throw OrderRuleException.accountPaymentInvalid(
                    "An ACCOUNT payment needs an admin's approval: send exactly one of authorizationToken or sessionToken.");
        }
        return authorizationToken != null
                ? adminAuthorizationService.consumeTokenInCurrentTransaction(authorizationToken)
                : adminAuthorizationService.validateSessionTokenForDiscount(sessionToken);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw OrderRuleException.discountInvalid(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
                // The card reference is optional (not every card machine gives one); a blank one
                // is stored as none.
                case CARD -> payment.setCardReference(blankToNull(paymentRequest.getCardReference()));
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
                    payment.setAuthorizedBy(authorizeAccountPayment(paymentRequest.getAuthorization()));
                }
            }

            order.getPayments().add(payment);
        }
    }
}
