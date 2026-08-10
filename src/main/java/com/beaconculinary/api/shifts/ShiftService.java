package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderPaymentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.RefundMethod;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@AllArgsConstructor
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderAdjustmentRepository orderAdjustmentRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AuthService authService;
    private final ShiftMapper shiftMapper;
    private final Clock clock;

    @Transactional
    public ShiftDto openShift(OpenShiftRequest request) {
        var currentUser = authService.getCurrentUser();

        var shift = new Shift();
        shift.setCashier(currentUser);
        shift.setOpeningFloat(request.getOpeningFloat());
        shift.setStatus(ShiftStatus.OPEN);

        // Stage 2.5: the till can only have one open shift system-wide, enforced by
        // idx_shifts_single_open (a filtered unique index on status = 'OPEN') rather than an
        // app-level check — flush immediately so a violation surfaces here, not at the
        // transaction's eventual commit-time flush, and translate it into a clean 409.
        try {
            shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException e) {
            throw new ShiftAlreadyOpenException();
        }

        return shiftMapper.toDto(shift);
    }

    @Transactional(readOnly = true)
    public ShiftSummaryDto getShiftSummary(Long id, String sessionToken) {
        Shift shift;
        if (sessionToken != null && !sessionToken.isBlank()) {
            // Stage 3.3: a valid management-session token grants read access to any shift's
            // cashup summary, regardless of ownership — the terminal is already inside an
            // admin-authorized context, so the usual owner-or-admin check doesn't apply.
            adminAuthorizationService.validateSessionToken(sessionToken);
            shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        } else {
            shift = loadShiftForCaller(id);
        }
        var breakdown = computeCashBreakdown(shift);

        var summary = new ShiftSummaryDto();
        summary.setOpeningFloat(shift.getOpeningFloat());
        summary.setCashSalesTotal(breakdown.cashSalesTotal());
        summary.setAdjustmentsTotal(breakdown.adjustmentsTotal());
        summary.setExpectedCash(breakdown.expectedCash());
        summary.setOrderCount(breakdown.orderCount());
        return summary;
    }

    @Transactional
    public ShiftDto closeShift(Long id, CloseShiftRequest request) {
        var shift = loadShiftForCaller(id);

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new ShiftAlreadyClosedException();
        }

        var breakdown = computeCashBreakdown(shift);
        var variance = request.getCountedCash().subtract(breakdown.expectedCash());

        if (variance.compareTo(BigDecimal.ZERO) != 0) {
            var authorization = request.getVarianceAuthorization();
            if (authorization == null) {
                throw new InvalidShiftRequestException("varianceAuthorization is required when countedCash does not match expectedCash.");
            }

            // Validate + burn the token first, in its own transaction, so any failure below
            // still leaves it unusable — same pattern as OrderAdjustmentService (Stage 2.6).
            var admin = adminAuthorizationService.consumeToken(authorization.getAuthorizationToken());

            if (authorization.getReasonCode() == ShiftVarianceReasonCode.OTHER
                    && (authorization.getNote() == null || authorization.getNote().isBlank())) {
                throw new InvalidShiftRequestException("note is required when reasonCode is OTHER.");
            }

            shift.setVarianceReasonCode(authorization.getReasonCode());
            shift.setVarianceNote(authorization.getNote());
            shift.setVarianceAuthorizedBy(admin);
        }

        shift.setClosingCash(request.getCountedCash());
        shift.setExpectedCash(breakdown.expectedCash());
        shift.setVariance(variance);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedAt(LocalDateTime.now(clock));
        shiftRepository.save(shift);

        return shiftMapper.toDto(shift);
    }

    public ShiftDto getCurrentShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(ShiftNotFoundException::new);

        return shiftMapper.toDto(shift);
    }

    private Shift loadShiftForCaller(Long id) {
        var shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        var currentUser = authService.getCurrentUser();

        var isOwner = shift.getCashier().getId().equals(currentUser.getId());
        if (!isOwner && currentUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Cannot access another cashier's shift.");
        }
        return shift;
    }

    private record CashBreakdown(BigDecimal cashSalesTotal, BigDecimal adjustmentsTotal, BigDecimal expectedCash, long orderCount) {
    }

    // Stage 4 Part D — expected_cash = opening_float + SUM(CASH-method OrderPayment amounts for
    // this shift) - SUM(CASH-refund_method adjustments authorized during this shift). Reworked
    // from Stage 2.5's original orders.payment_method-based formula to account for cash/card
    // splits (Stage 4 Part A) and account payments (Part B): an ACCOUNT_BALANCE-refunded
    // adjustment is excluded entirely, since no physical cash moved.
    private CashBreakdown computeCashBreakdown(Shift shift) {
        var cashSalesTotal = orderPaymentRepository.sumAmountByShiftIdAndMethod(shift.getId(), PaymentMethod.CASH);
        var adjustmentsTotal = orderAdjustmentRepository.sumAmountByShiftIdAndRefundMethod(shift.getId(), RefundMethod.CASH);
        var orderCount = orderRepository.countByShiftId(shift.getId());
        var expectedCash = shift.getOpeningFloat().add(cashSalesTotal).subtract(adjustmentsTotal);
        return new CashBreakdown(cashSalesTotal, adjustmentsTotal, expectedCash, orderCount);
    }
}
