package com.beaconculinary.api.oversight;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.orders.Order;
import com.beaconculinary.api.orders.OrderAdjustment;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.oversight.ShiftActivityDto.CountAmount;
import com.beaconculinary.api.oversight.ShiftActivityDto.ExpectedCashBreakdown;
import com.beaconculinary.api.oversight.ShiftActivityDto.Payment;
import com.beaconculinary.api.oversight.ShiftActivityDto.ProcessedAdjustment;
import com.beaconculinary.api.oversight.ShiftActivityDto.RecomputedParts;
import com.beaconculinary.api.oversight.ShiftActivityDto.ShiftDetail;
import com.beaconculinary.api.oversight.ShiftActivityDto.SoldOrder;
import com.beaconculinary.api.oversight.ShiftActivityDto.Totals;
import com.beaconculinary.api.shifts.Shift;
import com.beaconculinary.api.shifts.ShiftCashCalculator;
import com.beaconculinary.api.shifts.ShiftNotFoundException;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import com.beaconculinary.api.users.UserRefDto;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POS Oversight Part A — read-only, ADMIN-only views of shifts and what happened in each.
 *
 * <p>Two attributions are kept strictly apart: {@code orders} are those <em>sold</em> in the
 * shift (orders.shift_id), {@code adjustments} are those <em>processed</em> in it
 * (order_adjustments.shift_id), which may be on orders sold in an earlier shift — the same
 * split the Stage 2.5 cash reconciliation uses.
 */
@Service
@AllArgsConstructor
public class ShiftActivityService {
    private static final int DEFAULT_RANGE_DAYS = 7;
    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal MISMATCH_TOLERANCE = new BigDecimal("0.01");

    private final ShiftRepository shiftRepository;
    private final OrderRepository orderRepository;
    private final OrderAdjustmentRepository orderAdjustmentRepository;
    private final ShiftCashCalculator shiftCashCalculator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageDto<ShiftListRowDto> listShifts(LocalDate from, LocalDate to, Long ownerId,
                                               ShiftDisplayStatus status, int page, int size) {
        if (page < 0) {
            throw new InvalidShiftActivityRequestException("page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidShiftActivityRequestException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }

        var today = today();
        if (to == null) {
            to = from == null ? today : from.plusDays(DEFAULT_RANGE_DAYS - 1);
        }
        if (from == null) {
            from = to.minusDays(DEFAULT_RANGE_DAYS - 1);
        }
        if (from.isAfter(to)) {
            throw new InvalidShiftActivityRequestException("from must not be after to.");
        }

        var spec = openedOnOrAfter(startOfDayUtc(from))
                .and(openedBefore(startOfDayUtc(to.plusDays(1))))
                .and(ownedBy(ownerId))
                .and(hasDisplayStatus(status, startOfDayUtc(today)));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id")));
        var shifts = shiftRepository.findAll(spec, pageable);

        var orderCounts = new HashMap<Long, Long>();
        var ids = shifts.getContent().stream().map(Shift::getId).toList();
        if (!ids.isEmpty()) {
            for (var row : orderRepository.countByShiftIds(ids)) {
                orderCounts.put((Long) row[0], (Long) row[1]);
            }
        }

        return PageDto.of(shifts, shift -> toListRow(shift, orderCounts.getOrDefault(shift.getId(), 0L), today));
    }

    @Transactional(readOnly = true)
    public ShiftActivityDto getActivity(Long shiftId) {
        var shift = shiftRepository.findById(shiftId).orElseThrow(ShiftNotFoundException::new);
        var today = today();

        var orders = orderRepository.findSoldInShiftWithPayments(shiftId);
        var adjustments = orderAdjustmentRepository.findProcessedInShift(shiftId);

        // Order flags describe the order whichever shift processed the adjustment.
        var discountedOrderIds = new HashSet<Long>();
        var extrasRemovedOrderIds = new HashSet<Long>();
        for (var adjustment : orderAdjustmentRepository.findOnOrdersSoldInShift(shiftId)) {
            switch (AdjustmentType.of(adjustment)) {
                case DISCOUNT -> discountedOrderIds.add(adjustment.getOrder().getId());
                case EXTRAS_REMOVED -> extrasRemovedOrderIds.add(adjustment.getOrder().getId());
                default -> {
                }
            }
        }

        var row = toListRow(shift, orders.size(), today);
        var detail = new ShiftDetail(row.shiftId(), row.owner(), row.displayStatus(), row.openedAt(), row.closedAt(),
                row.openingFloat(), row.closingCash(), row.expectedCash(), row.variance(), row.orderCount(),
                UserRefDto.of(shift.getClosedBy()), shift.getVarianceReasonCode(), shift.getVarianceNote(),
                UserRefDto.of(shift.getVarianceAuthorizedBy()));

        return new ShiftActivityDto(
                detail,
                breakdown(shift),
                totals(orders, adjustments),
                orders.stream().map(order -> toSoldOrder(order, discountedOrderIds, extrasRemovedOrderIds)).toList(),
                adjustments.stream().map(adjustment -> toProcessedAdjustment(adjustment, shiftId)).toList());
    }

    private ShiftListRowDto toListRow(Shift shift, long orderCount, LocalDate today) {
        var open = shift.getStatus() == ShiftStatus.OPEN;
        // Open: live, from the same calculator as GET /shifts/{id}/summary. Closed: the stored snapshot.
        var expectedCash = open
                ? shiftCashCalculator.compute(shift.getId(), shift.getOpeningFloat()).expectedCash()
                : shift.getExpectedCash();

        return new ShiftListRowDto(
                shift.getId(),
                UserRefDto.withRole(shift.getCashier()),
                displayStatus(shift, today),
                UtcTimestamps.toInstant(shift.getOpenedAt()),
                open ? null : UtcTimestamps.toInstant(shift.getClosedAt()),
                shift.getOpeningFloat(),
                open ? null : shift.getClosingCash(),
                expectedCash,
                open ? null : shift.getVariance(),
                orderCount);
    }

    private ShiftDisplayStatus displayStatus(Shift shift, LocalDate today) {
        if (shift.getStatus() == ShiftStatus.CLOSED) {
            return ShiftDisplayStatus.CLOSED;
        }
        var openedOn = LocalDate.ofInstant(UtcTimestamps.toInstant(shift.getOpenedAt()), ClockConfig.BUSINESS_ZONE);
        return openedOn.isBefore(today) ? ShiftDisplayStatus.OVERDUE : ShiftDisplayStatus.ON_SHIFT;
    }

    private ExpectedCashBreakdown breakdown(Shift shift) {
        var live = shiftCashCalculator.compute(shift.getId(), shift.getOpeningFloat());

        if (shift.getStatus() == ShiftStatus.OPEN) {
            return new ExpectedCashBreakdown(live.openingFloat(), live.cashSales(), live.cashRefunds(), live.expectedCash(),
                    BreakdownSource.LIVE, null, null, null);
        }

        if (shift.getCashSalesAtClose() == null || shift.getCashRefundsAtClose() == null) {
            // Closed before V82: parts weren't stored, and the shift may have closed under an
            // earlier formula, so show the recomputation without judging it.
            return new ExpectedCashBreakdown(live.openingFloat(), live.cashSales(), live.cashRefunds(), live.expectedCash(),
                    BreakdownSource.RECALCULATED, null, null, shift.getExpectedCash());
        }

        // Closed after V82: return the stored parts. A difference from the recomputation should
        // never happen (nothing can attach to a closed shift), so surface it as an integrity alarm.
        var mismatch = differs(shift.getCashSalesAtClose(), live.cashSales())
                || differs(shift.getCashRefundsAtClose(), live.cashRefunds())
                || differs(shift.getExpectedCash(), live.expectedCash());
        return new ExpectedCashBreakdown(shift.getOpeningFloat(), shift.getCashSalesAtClose(), shift.getCashRefundsAtClose(),
                shift.getExpectedCash(), BreakdownSource.SNAPSHOT, mismatch,
                mismatch ? new RecomputedParts(live.cashSales(), live.cashRefunds(), live.expectedCash()) : null,
                null);
    }

    private static boolean differs(BigDecimal stored, BigDecimal recomputed) {
        return stored == null || stored.subtract(recomputed).abs().compareTo(MISMATCH_TOLERANCE) >= 0;
    }

    private Totals totals(List<Order> orders, List<OrderAdjustment> adjustments) {
        // Original collected amounts (order_payments rows are never reduced by adjustments).
        var salesByMethod = new EnumMap<PaymentMethod, BigDecimal>(PaymentMethod.class);
        for (var method : PaymentMethod.values()) {
            salesByMethod.put(method, BigDecimal.ZERO);
        }
        for (var order : orders) {
            for (var payment : order.getPayments()) {
                salesByMethod.merge(payment.getMethod(), payment.getAmount(), BigDecimal::add);
            }
        }

        var counts = new EnumMap<AdjustmentType, Long>(AdjustmentType.class);
        var amounts = new EnumMap<AdjustmentType, BigDecimal>(AdjustmentType.class);
        for (var adjustment : adjustments) {
            var type = AdjustmentType.of(adjustment);
            counts.merge(type, 1L, Long::sum);
            amounts.merge(type, adjustment.getAmount(), BigDecimal::add);
        }

        return new Totals(orders.size(), salesByMethod,
                countAmount(AdjustmentType.VOID, counts, amounts),
                countAmount(AdjustmentType.REFUND, counts, amounts),
                countAmount(AdjustmentType.DISCOUNT, counts, amounts),
                countAmount(AdjustmentType.EXTRAS_REMOVED, counts, amounts));
    }

    private static CountAmount countAmount(AdjustmentType type, Map<AdjustmentType, Long> counts,
                                           Map<AdjustmentType, BigDecimal> amounts) {
        return new CountAmount(counts.getOrDefault(type, 0L), amounts.getOrDefault(type, BigDecimal.ZERO));
    }

    private SoldOrder toSoldOrder(Order order, Set<Long> discountedOrderIds, Set<Long> extrasRemovedOrderIds) {
        var payments = new ArrayList<Payment>();
        for (var payment : order.getPayments()) {
            payments.add(new Payment(payment.getMethod(), payment.getAmount()));
        }
        return new SoldOrder(order.getId(), order.getOrderNumber(), UtcTimestamps.toInstant(order.getCreatedAt()),
                UserRefDto.withRole(order.getCashier()), order.getStatus(), order.getOriginalTotal(), order.getTotal(),
                payments, discountedOrderIds.contains(order.getId()), extrasRemovedOrderIds.contains(order.getId()));
    }

    private ProcessedAdjustment toProcessedAdjustment(OrderAdjustment adjustment, Long shiftId) {
        var order = adjustment.getOrder();
        var soldInShiftId = order.getShift().getId();
        return new ProcessedAdjustment(adjustment.getId(), UtcTimestamps.toInstant(adjustment.getCreatedAt()),
                order.getId(), order.getOrderNumber(), AdjustmentType.of(adjustment), adjustment.getAmount(),
                adjustment.getRefundMethod(), adjustment.getReasonCode(), adjustment.getNote(),
                UserRefDto.of(adjustment.getRequestedBy()), UserRefDto.of(adjustment.getAuthorizedBy()),
                soldInShiftId, !soldInShiftId.equals(shiftId));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    /** The UTC wall-clock value (as stored in opened_at) at which a Johannesburg calendar day starts. */
    private static LocalDateTime startOfDayUtc(LocalDate businessDay) {
        return UtcTimestamps.toUtc(businessDay.atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant());
    }

    private static Specification<Shift> openedOnOrAfter(LocalDateTime utc) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("openedAt"), utc);
    }

    private static Specification<Shift> openedBefore(LocalDateTime utc) {
        return (root, query, cb) -> cb.lessThan(root.get("openedAt"), utc);
    }

    private static Specification<Shift> ownedBy(Long ownerId) {
        return ownerId == null ? null : (root, query, cb) -> cb.equal(root.get("cashier").get("id"), ownerId);
    }

    private static Specification<Shift> hasDisplayStatus(ShiftDisplayStatus status, LocalDateTime todayStartUtc) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CLOSED -> (root, query, cb) -> cb.equal(root.get("status"), ShiftStatus.CLOSED);
            case ON_SHIFT -> (root, query, cb) -> cb.and(cb.equal(root.get("status"), ShiftStatus.OPEN),
                    cb.greaterThanOrEqualTo(root.get("openedAt"), todayStartUtc));
            case OVERDUE -> (root, query, cb) -> cb.and(cb.equal(root.get("status"), ShiftStatus.OPEN),
                    cb.lessThan(root.get("openedAt"), todayStartUtc));
        };
    }
}
