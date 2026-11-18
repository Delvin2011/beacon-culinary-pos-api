package com.beaconculinary.api.offline.sync;

import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.inventory.LightMealStockService;
import com.beaconculinary.api.inventory.VatSettings;
import com.beaconculinary.api.menu.DailyComponentStock;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOption;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.DailyPlanItemStatus;
import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.offline.OfflineSequenceStore;
import com.beaconculinary.api.offline.exceptions.SaleExceptionCode;
import com.beaconculinary.api.offline.exceptions.SaleExceptionStore;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.ExceptionRef;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.Sale;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SaleResult;
import com.beaconculinary.api.orders.Order;
import com.beaconculinary.api.orders.OrderKind;
import com.beaconculinary.api.orders.OrderLine;
import com.beaconculinary.api.orders.OrderLineExtra;
import com.beaconculinary.api.orders.OrderMapper;
import com.beaconculinary.api.orders.OrderNumberAllocator;
import com.beaconculinary.api.orders.OrderPayment;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.OrderStatusEvent;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.VatRule;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * O4: records one completed offline sale (contract section 5). Never refuses: everything the server
 * would have done differently becomes a sale exception (section 6). No check against the server's
 * clock, no station rows, no events to the kitchen, bar or public board. Runs in its caller's
 * transaction: OfflineSyncService gives each sale its own.
 */
@Component
public class OfflineSaleRecorder {
    static final Duration CLOCK_SKEW_LIMIT = Duration.ofMinutes(5);
    static final Duration FUTURE_SALE_LIMIT = Duration.ofMinutes(1);
    private static final int GRANT_LOCK_TIMEOUT_MILLIS = 15_000;

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final OrderNumberAllocator orderNumberAllocator;
    private final OrderStatusEventRepository statusEvents;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final DailyMealOptionRepository optionRepository;
    private final DailyComponentStockRepository stockRepository;
    private final LightMealStockService lightMealStockService;
    private final ZReadings zReadings;
    private final VatSettings vatSettings;
    private final OfflineProperties properties;
    private final OfflineSequenceStore sequences;
    private final SaleExceptionStore exceptions;
    private final AppLock appLock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OfflineSaleRecorder(OrderRepository orderRepository, OrderMapper orderMapper,
                               OrderNumberAllocator orderNumberAllocator, OrderStatusEventRepository statusEvents,
                               ShiftRepository shiftRepository, UserRepository userRepository,
                               AccountRepository accountRepository, DailyMealOptionRepository optionRepository,
                               DailyComponentStockRepository stockRepository, LightMealStockService lightMealStockService,
                               ZReadings zReadings, VatSettings vatSettings, OfflineProperties properties,
                               OfflineSequenceStore sequences, SaleExceptionStore exceptions, AppLock appLock,
                               JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
        this.orderNumberAllocator = orderNumberAllocator;
        this.statusEvents = statusEvents;
        this.shiftRepository = shiftRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.optionRepository = optionRepository;
        this.stockRepository = stockRepository;
        this.lightMealStockService = lightMealStockService;
        this.zReadings = zReadings;
        this.vatSettings = vatSettings;
        this.properties = properties;
        this.sequences = sequences;
        this.exceptions = exceptions;
        this.appLock = appLock;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Where the batch came from and when it arrived. */
    public record Context(UUID deviceId, String deviceStatus, Instant receivedAt) {
        boolean deviceRevoked() {
            return !"VALID".equals(deviceStatus);
        }
    }

    /** One exception to record. */
    private record Flag(SaleExceptionCode code, Map<String, Object> detail) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SaleResult record(Sale sale, OfflineGrantStore.GrantRow grant, Context context) {
        var existing = orderRepository.findByClientOrderId(sale.clientOrderId());
        if (existing.isPresent()) {
            return duplicate(sale, existing.get());
        }
        // One grant's sales one at a time: the account cap and the till's numbering are per grant.
        appLock.lock("offline-sync-grant:" + grant.id(), GRANT_LOCK_TIMEOUT_MILLIS);
        existing = orderRepository.findByClientOrderId(sale.clientOrderId());
        if (existing.isPresent()) {
            return duplicate(sale, existing.get());
        }

        var flags = new ArrayList<Flag>();
        var shift = shiftRepository.findById(grant.shiftId()).orElseThrow();
        var cashier = userRepository.findById(grant.cashierId()).orElseThrow();
        var soldAt = sale.soldAt();
        var soldAtUtc = UtcTimestamps.toUtc(soldAt);
        var saleDay = LocalDate.ofInstant(soldAt, ClockConfig.BUSINESS_ZONE);

        checkTimes(sale, grant, context, flags);
        if (context.deviceRevoked()) {
            flags.add(flag(SaleExceptionCode.DEVICE_REVOKED, "status", context.deviceStatus()));
        }
        if ((sale.shiftId() != null && sale.shiftId() != grant.shiftId())
                || (sale.cashierId() != null && sale.cashierId() != grant.cashierId())) {
            flags.add(flag(SaleExceptionCode.GRANT_MISMATCH, "sentShiftId", sale.shiftId(), "sentCashierId", sale.cashierId(),
                    "grantShiftId", grant.shiftId(), "grantCashierId", grant.cashierId()));
        }
        if (Boolean.TRUE.equals(sale.unlockedOffline())) {
            flags.add(new Flag(SaleExceptionCode.UNLOCKED_OFFLINE, Map.of()));
        }
        if (shift.getStatus() == ShiftStatus.CLOSED) {
            flags.add(flag(SaleExceptionCode.LATE_AFTER_SHIFT_CLOSE, "shiftId", shift.getId(),
                    "closedAt", UtcTimestamps.toInstant(shift.getClosedAt())));
        }

        LocalDate bookedDay = null;
        if (zReadings.forDay(saleDay).isPresent()) {
            bookedDay = saleDay.plusDays(1);
            while (zReadings.forDay(bookedDay).isPresent()) {
                bookedDay = bookedDay.plusDays(1);
            }
            flags.add(flag(SaleExceptionCode.LATE_AFTER_Z, "saleDay", saleDay, "bookedDay", bookedDay));
        }
        var orderDate = bookedDay != null ? bookedDay : saleDay;

        var order = new Order();
        order.setShift(shift);
        order.setCashier(cashier);
        order.setDeviceId(grant.deviceId());
        order.setClientOrderId(sale.clientOrderId());
        order.setOrderDate(orderDate);
        order.setOrderKind(OrderKind.SALE);
        order.setStatus(OrderStatus.COLLECTED);
        order.setRecordedOffline(true);
        order.setOfflineGrantId(grant.id());
        order.setSoldAt(soldAtUtc);
        order.setOfflineReceiptNumber(sale.offlineReceiptNumber());
        order.setCollectionNumber(sale.collectionNumber());
        order.setOfflineTradingDay(saleDay);
        order.setBookedTradingDay(bookedDay);
        numbering(sale, grant, saleDay, order, flags);

        var linesTotal = lines(sale, saleDay, soldAt, order, flags);
        totals(sale, linesTotal, order, flags);
        payments(sale, grant, order, flags);

        order.setOrderNumber(orderNumberAllocator.next(orderDate));
        orderRepository.saveAndFlush(order);

        var event = new OrderStatusEvent();
        event.setOrder(order);
        event.setFromStatus(null);
        event.setToStatus(OrderStatus.COLLECTED);
        event.setChangedBy(cashier);
        event.setChangedAt(UtcTimestamps.toUtc(context.receivedAt()));
        statusEvents.save(event);

        var movements = lightMealStockService.deductForSale(order.getLines(), cashier, soldAtUtc);
        stockAfterCount(movements, soldAtUtc, flags);

        var refs = new ArrayList<ExceptionRef>();
        for (var flag : flags) {
            exceptions.insert(order.getId(), flag.code(), json(flag.detail()), grant.deviceId(), orderDate);
            refs.add(new ExceptionRef(flag.code().name(), flag.code().severity().name()));
        }
        var dto = orderMapper.toDto(order);
        return new SaleResult(sale.clientOrderId().toString(), refs.isEmpty() ? "CREATED" : "CREATED_WITH_EXCEPTIONS",
                order.getId(), order.getOrderNumber(), dto.getReceiptNumber(), order.getCollectionNumber(), null, null, refs);
    }

    // ---- checks ----

    private void checkTimes(Sale sale, OfflineGrantStore.GrantRow grant, Context context, List<Flag> flags) {
        if (sale.tillClockAtSendUtc() != null) {
            var skew = Duration.between(context.receivedAt(), sale.tillClockAtSendUtc());
            if (skew.abs().compareTo(CLOCK_SKEW_LIMIT) > 0) {
                flags.add(flag(SaleExceptionCode.CLOCK_SKEW, "skewMs", skew.toMillis()));
            }
        }
        if (sale.soldAt().isAfter(context.receivedAt().plus(FUTURE_SALE_LIMIT))) {
            flags.add(flag(SaleExceptionCode.FUTURE_SALE, "soldAt", sale.soldAt(), "receivedAt", context.receivedAt()));
        }
        var issued = UtcTimestamps.toInstant(grant.issuedAt());
        var expires = UtcTimestamps.toInstant(grant.expiresAt());
        var revoked = UtcTimestamps.toInstant(grant.revokedAt());
        var soldAt = sale.soldAt();
        if (soldAt.isBefore(issued) || !soldAt.isBefore(expires) || (revoked != null && soldAt.isAfter(revoked))) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("issuedAt", issued);
            detail.put("expiresAt", expires);
            detail.put("revokedAt", revoked);
            detail.put("revokeReason", grant.revokeReason() == null ? null : grant.revokeReason().name());
            flags.add(new Flag(SaleExceptionCode.OUTSIDE_GRANT, detail));
        }
    }

    private void numbering(Sale sale, OfflineGrantStore.GrantRow grant, LocalDate saleDay, Order order, List<Flag> flags) {
        var seq = sale.offlineSeq();
        if (seq == null) {
            return;
        }
        var clash = jdbc.queryForList("""
                SELECT [id] FROM [orders] WHERE [device_id] = ? AND [offline_trading_day] = ? AND [offline_seq] = ?""",
                Long.class, grant.deviceId().toString(), java.sql.Date.valueOf(saleDay), seq);
        if (clash.isEmpty()) {
            order.setOfflineSeq(seq);
        } else {
            flags.add(flag(SaleExceptionCode.OFFLINE_NUMBER_CLASH, "offlineSeq", seq, "otherOrderId", clash.get(0)));
        }
        sequences.recordSeen(grant.deviceId(), saleDay, seq, LocalDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC));
    }

    /** Builds the lines as charged; returns what they add up to (lines and extras). */
    private BigDecimal lines(Sale sale, LocalDate saleDay, Instant soldAt, Order order, List<Flag> flags) {
        var soldAtLocal = soldAt.atZone(ClockConfig.BUSINESS_ZONE).toLocalTime();
        var total = BigDecimal.ZERO;
        for (int i = 0; i < sale.lines().size(); i++) {
            var input = sale.lines().get(i);
            var option = input.dailyMealOptionId() == null ? null : optionRepository.findById(input.dailyMealOptionId()).orElse(null);
            var line = new OrderLine();
            line.setOrder(order);
            line.setDailyMealOption(option);
            line.setName(input.name() != null ? input.name() : option != null ? option.getName() : "Unknown item");
            line.setStation(station(input.station(), option));
            line.setUnitPrice(input.unitPrice());
            line.setQuantity(input.quantity());
            var lineTotal = input.lineTotal() != null ? input.lineTotal()
                    : input.unitPrice().multiply(BigDecimal.valueOf(input.quantity()));
            line.setLineTotal(lineTotal);
            total = total.add(lineTotal);

            if (option == null) {
                flags.add(flag(SaleExceptionCode.UNKNOWN_ITEM, "lineIndex", i, "name", line.getName(), "charged", input.unitPrice()));
            } else {
                checkOption(i, option, input.unitPrice(), saleDay, soldAtLocal, flags);
                decrementPortions(option, input.quantity(), flags);
            }
            if (input.extras() != null) {
                for (var extraInput : input.extras()) {
                    var stock = extraInput.dailyComponentStockId() == null ? null
                            : stockRepository.findById(extraInput.dailyComponentStockId()).orElse(null);
                    var extra = new OrderLineExtra();
                    extra.setOrderLine(line);
                    extra.setDailyComponentStock(stock);
                    extra.setComponentName(extraInput.name() != null ? extraInput.name()
                            : stock != null ? stock.getComponentCatalog().getName() : "Unknown extra");
                    extra.setPriceDelta(extraInput.priceDelta());
                    extra.setQuantity(extraInput.quantity());
                    var extraTotal = extraInput.lineTotal() != null ? extraInput.lineTotal()
                            : extraInput.priceDelta().multiply(BigDecimal.valueOf(extraInput.quantity()));
                    extra.setLineTotal(extraTotal);
                    total = total.add(extraTotal);
                    line.getExtras().add(extra);
                    if (stock == null) {
                        flags.add(flag(SaleExceptionCode.UNKNOWN_ITEM, "lineIndex", i, "name", extra.getComponentName(),
                                "charged", extraInput.priceDelta()));
                    } else {
                        if (stock.getExtraPrice() != null && stock.getExtraPrice().compareTo(extraInput.priceDelta()) != 0) {
                            flags.add(flag(SaleExceptionCode.PRICE_MISMATCH, "lineIndex", i, "componentStockId", stock.getId(),
                                    "charged", extraInput.priceDelta(), "serverPrice", stock.getExtraPrice()));
                        }
                        decrementBuffer(stock, extraInput.quantity(), flags);
                    }
                }
            }
            order.getLines().add(line);
        }
        return total;
    }

    private void checkOption(int index, DailyMealOption option, BigDecimal charged, LocalDate saleDay,
                             java.time.LocalTime soldAtLocal, List<Flag> flags) {
        if (option.getPrice() != null && option.getPrice().compareTo(charged) != 0) {
            flags.add(flag(SaleExceptionCode.PRICE_MISMATCH, "lineIndex", index, "optionId", option.getId(),
                    "charged", charged, "serverPrice", option.getPrice()));
        }
        String unavailable = null;
        if (!saleDay.equals(option.getOptionDate())) {
            unavailable = "OTHER_DAY";
        } else if (option.getStatus() != DailyPlanItemStatus.READY) {
            unavailable = "NOT_READY";
        } else if (option.getMealCatalog() != null && option.getMealCatalog().isOutOn(saleDay)) {
            unavailable = "OUT_TODAY";
        }
        if (unavailable != null) {
            flags.add(flag(SaleExceptionCode.ITEM_UNAVAILABLE, "lineIndex", index, "optionId", option.getId(), "reason", unavailable));
        }
        var period = option.getMealPeriod();
        if (period != null && !period.isActiveAt(soldAtLocal)) {
            flags.add(flag(SaleExceptionCode.PERIOD_CLOSED, "lineIndex", index, "periodId", period.getId()));
        }
    }

    /** Never below 0 (the CHECK constraint): the shortfall is recorded instead. Unlimited items are skipped. */
    private void decrementPortions(DailyMealOption option, int quantity, List<Flag> flags) {
        var before = jdbc.queryForList("""
                UPDATE [daily_meal_options]
                SET [portions_remaining] = CASE WHEN [portions_remaining] >= ? THEN [portions_remaining] - ? ELSE 0 END
                OUTPUT deleted.[portions_remaining]
                WHERE [id] = ? AND [portions_remaining] IS NOT NULL""", Integer.class, quantity, quantity, option.getId());
        if (!before.isEmpty() && before.get(0) != null && before.get(0) < quantity) {
            flags.add(flag(SaleExceptionCode.OVERSOLD, "optionId", option.getId(), "requested", quantity,
                    "shortfall", quantity - before.get(0)));
        }
    }

    private void decrementBuffer(DailyComponentStock stock, int quantity, List<Flag> flags) {
        var before = jdbc.queryForList("""
                UPDATE [daily_component_stock]
                SET [buffer_remaining] = CASE WHEN [buffer_remaining] >= ? THEN [buffer_remaining] - ? ELSE 0 END
                OUTPUT deleted.[buffer_remaining]
                WHERE [id] = ? AND [buffer_remaining] IS NOT NULL""", Integer.class, quantity, quantity, stock.getId());
        if (!before.isEmpty() && before.get(0) != null && before.get(0) < quantity) {
            flags.add(flag(SaleExceptionCode.OVERSOLD, "componentStockId", stock.getId(), "requested", quantity,
                    "shortfall", quantity - before.get(0)));
        }
    }

    private void totals(Sale sale, BigDecimal linesTotal, Order order, List<Flag> flags) {
        var subtotal = sale.subtotal() != null ? sale.subtotal() : linesTotal;
        var total = sale.total() != null ? sale.total() : subtotal;
        if (linesTotal.compareTo(subtotal) != 0 || subtotal.compareTo(total) != 0) {
            flags.add(flag(SaleExceptionCode.TOTALS_MISMATCH, "linesTotal", linesTotal, "subtotal", subtotal, "total", total));
        }
        order.setSubtotal(subtotal);
        order.setTotal(total);
        order.setPreDiscountTotal(total);
        order.setSaleDiscount(BigDecimal.ZERO);
        order.setOriginalTotal(total);

        var serverRate = VatRule.storedRate(vatSettings.standardRate());
        var serverVat = VatRule.includedVat(total, serverRate);
        order.setVatRate(serverRate);
        order.setVatIncludedAmount(serverVat);
        var rateDiffers = sale.vatRate() != null && sale.vatRate().compareTo(serverRate) != 0;
        var vatDiffers = sale.vatIncludedAmount() != null && sale.vatIncludedAmount().compareTo(serverVat) != 0;
        if (rateDiffers || vatDiffers) {
            flags.add(flag(SaleExceptionCode.VAT_MISMATCH, "printedRate", sale.vatRate(), "printedVat", sale.vatIncludedAmount(),
                    "serverRate", serverRate, "serverVat", serverVat));
        }
    }

    private void payments(Sale sale, OfflineGrantStore.GrantRow grant, Order order, List<Flag> flags) {
        var paid = BigDecimal.ZERO;
        var problems = new ArrayList<String>();
        for (var input : sale.payments() == null ? List.<OfflineSaleDtos.Payment>of() : sale.payments()) {
            var method = PaymentMethod.valueOf(input.method());
            if (input.amount().signum() <= 0) {
                problems.add(method + " payment of " + input.amount() + " not stored");
                continue;
            }
            var payment = new OrderPayment();
            payment.setOrder(order);
            payment.setMethod(method);
            payment.setAmount(input.amount());
            paid = paid.add(input.amount());
            switch (method) {
                case CASH -> {
                    payment.setAmountTendered(input.amountTendered());
                    payment.setChangeDue(input.changeDue());
                    if (input.amountTendered() != null && input.amountTendered().compareTo(input.amount()) < 0) {
                        problems.add("cash tendered is less than the amount");
                    } else if (input.amountTendered() != null && input.changeDue() != null
                            && input.amountTendered().subtract(input.amount()).compareTo(input.changeDue()) != 0) {
                        problems.add("change doesn't match tendered minus amount");
                    }
                }
                case CARD -> {
                    var reference = input.cardReference() == null || input.cardReference().isBlank() ? null : input.cardReference().trim();
                    payment.setCardReference(reference);
                    if (reference == null) {
                        flags.add(flag(SaleExceptionCode.CARD_REFERENCE_MISSING, "amount", input.amount()));
                    }
                }
                case ACCOUNT -> account(input, grant, payment, flags);
            }
            order.getPayments().add(payment);
        }
        if (paid.compareTo(order.getTotal()) != 0 || !problems.isEmpty()) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("paymentsTotal", paid);
            detail.put("total", order.getTotal());
            detail.put("problem", problems.isEmpty() ? "payments don't add up to the total" : String.join("; ", problems));
            flags.add(new Flag(SaleExceptionCode.PAYMENT_MISMATCH, detail));
        }
    }

    private void account(OfflineSaleDtos.Payment input, OfflineGrantStore.GrantRow grant, OrderPayment payment, List<Flag> flags) {
        var account = input.accountId() == null ? null : accountRepository.findById(input.accountId()).orElse(null);
        payment.setAccount(account);
        if (account == null || !account.isActive()) {
            flags.add(flag(SaleExceptionCode.ACCOUNT_INACTIVE, "accountId", input.accountId(),
                    "reason", account == null ? "UNKNOWN" : "INACTIVE"));
        }
        flags.add(flag(SaleExceptionCode.ACCOUNT_APPROVAL_PENDING, "accountId", input.accountId(),
                "accountNumber", account == null ? null : account.getAccountNumber(), "amount", input.amount()));
        if (account != null) {
            var earlier = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(p.[amount]), 0) FROM [order_payments] p JOIN [orders] o ON o.[id] = p.[order_id]
                    WHERE o.[offline_grant_id] = ? AND p.[method] = 'ACCOUNT' AND p.[account_id] = ?""",
                    BigDecimal.class, grant.id().toString(), account.getId());
            var capTotal = earlier.add(input.amount());
            if (capTotal.compareTo(properties.getAccountCap()) > 0) {
                flags.add(flag(SaleExceptionCode.ACCOUNT_OVER_CAP, "accountId", account.getId(), "capTotal", capTotal,
                        "cap", properties.getAccountCap()));
            }
        }
    }

    /** INFO: a light-meal ingredient this sale used was counted in a stock take submitted after soldAt. */
    private void stockAfterCount(List<com.beaconculinary.api.inventory.IngredientStockMovement> movements,
                                 LocalDateTime soldAtUtc, List<Flag> flags) {
        if (movements.isEmpty()) {
            return;
        }
        var location = movements.get(0).getLocation().getId();
        var ingredients = movements.stream().map(m -> m.getIngredient().getId()).distinct().toList();
        var placeholders = String.join(", ", java.util.Collections.nCopies(ingredients.size(), "?"));
        var args = new ArrayList<Object>();
        args.add(location);
        args.add(soldAtUtc);
        args.addAll(ingredients);
        var found = jdbc.queryForList("""
                SELECT TOP (1) st.[id] AS [stock_take_id], l.[ingredient_id] FROM [stock_takes] st
                JOIN [stock_take_lines] l ON l.[stock_take_id] = st.[id]
                WHERE st.[location_id] = ? AND st.[submitted_at] > ? AND st.[status] <> 'REJECTED'
                  AND l.[ingredient_id] IN (""" + placeholders + ") ORDER BY st.[submitted_at]", args.toArray());
        if (!found.isEmpty()) {
            flags.add(flag(SaleExceptionCode.STOCK_AFTER_COUNT, "stockTakeId", found.get(0).get("stock_take_id"),
                    "ingredientId", found.get(0).get("ingredient_id")));
        }
    }

    // ---- duplicates ----

    private SaleResult duplicate(Sale sale, Order existing) {
        var refs = new ArrayList<ExceptionRef>();
        var differences = differences(sale, existing);
        if (!differences.isEmpty() && !exceptions.exists(existing.getId(), SaleExceptionCode.DUPLICATE_DIFFERS)) {
            var day = existing.getBookedTradingDay() != null ? existing.getBookedTradingDay() : existing.getOrderDate();
            exceptions.insert(existing.getId(), SaleExceptionCode.DUPLICATE_DIFFERS,
                    json(Map.of("differences", differences)), existing.getDeviceId(), day);
            refs.add(new ExceptionRef(SaleExceptionCode.DUPLICATE_DIFFERS.name(),
                    SaleExceptionCode.DUPLICATE_DIFFERS.severity().name()));
        }
        var dto = orderMapper.toDto(existing);
        return new SaleResult(sale.clientOrderId().toString(), "DUPLICATE", existing.getId(), existing.getOrderNumber(),
                dto.getReceiptNumber(), existing.getCollectionNumber(), null, null, refs);
    }

    private static List<String> differences(Sale sale, Order existing) {
        var differences = new ArrayList<String>();
        if (sale.total() != null && existing.getOriginalTotal() != null && sale.total().compareTo(existing.getOriginalTotal()) != 0) {
            differences.add("total " + sale.total() + " vs " + existing.getOriginalTotal());
        }
        var sentLines = sale.lines().stream()
                .map(l -> Objects.toString(l.dailyMealOptionId()) + "x" + l.quantity() + "@" + l.unitPrice().stripTrailingZeros().toPlainString())
                .sorted().toList();
        var storedLines = existing.getLines().stream()
                .map(l -> Objects.toString(l.getDailyMealOption() == null ? null : l.getDailyMealOption().getId()) + "x"
                        + l.getQuantity() + "@" + l.getUnitPrice().stripTrailingZeros().toPlainString())
                .sorted().toList();
        if (!sentLines.equals(storedLines)) {
            differences.add("lines differ");
        }
        var sentPayments = (sale.payments() == null ? List.<OfflineSaleDtos.Payment>of() : sale.payments()).stream()
                .filter(p -> p.amount() != null && p.amount().signum() > 0)
                .map(p -> p.method() + ":" + p.amount().stripTrailingZeros().toPlainString()).sorted().toList();
        var storedPayments = existing.getPayments().stream()
                .map(p -> p.getMethod() + ":" + p.getAmount().stripTrailingZeros().toPlainString())
                .sorted(Comparator.naturalOrder()).toList();
        if (!sentPayments.equals(storedPayments)) {
            differences.add("payments differ");
        }
        return differences;
    }

    // ---- helpers ----

    private static Station station(String sent, DailyMealOption option) {
        if (sent != null) {
            try {
                return Station.valueOf(sent);
            } catch (IllegalArgumentException ignored) {
                // fall through to the option's station
            }
        }
        if (option != null && option.getMealCatalog() != null && option.getMealCatalog().getStation() != null) {
            return option.getMealCatalog().getStation();
        }
        return Station.NONE;
    }

    private static Flag flag(SaleExceptionCode code, Object... keyValues) {
        var detail = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            detail.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Flag(code, detail);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Couldn't write the exception detail", e);
        }
    }
}
