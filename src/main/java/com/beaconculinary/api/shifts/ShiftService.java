package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.UserRefDto;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

@Service
@AllArgsConstructor
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final ShiftCashCalculator shiftCashCalculator;
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
        var breakdown = shiftCashCalculator.compute(shift.getId(), shift.getOpeningFloat());

        var summary = new ShiftSummaryDto();
        summary.setOpeningFloat(shift.getOpeningFloat());
        summary.setCashSalesTotal(breakdown.cashSales());
        summary.setAdjustmentsTotal(breakdown.cashRefunds());
        summary.setExpectedCash(breakdown.expectedCash());
        summary.setOrderCount(breakdown.orderCount());
        return summary;
    }

    @Transactional
    public ShiftDto closeShift(Long id, CloseShiftRequest request) {
        // POS Oversight B3: exclusive row lock before anything is read or computed, so an order
        // or adjustment can't attach to this shift between the expected-cash calculation and
        // the close being written (see ShiftRepository.findOpenShiftWithSharedLock). Taken
        // before the load so the status check below sees any concurrent close.
        shiftRepository.lockForClose(id).orElseThrow(ShiftNotFoundException::new);
        var shift = loadShiftForCaller(id);

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new ShiftAlreadyClosedException();
        }

        var breakdown = shiftCashCalculator.compute(shift.getId(), shift.getOpeningFloat());
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
        shift.setCashSalesAtClose(breakdown.cashSales());
        shift.setCashRefundsAtClose(breakdown.cashRefunds());
        shift.setVariance(variance);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedBy(authService.getCurrentUser());
        // UTC, matching opened_at's SYSUTCDATETIME() default (V84 corrected older rows).
        shift.setClosedAt(UtcTimestamps.nowUtc(clock));
        shiftRepository.save(shift);

        return shiftMapper.toDto(shift);
    }

    public ShiftDto getCurrentShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(ShiftNotFoundException::new);

        return shiftMapper.toDto(shift);
    }

    /** POS Oversight B2: the till's open shift whoever owns it — unlike getCurrentShift, which
     * only finds the caller's own. */
    @Transactional(readOnly = true)
    public OpenShiftDto getOpenShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow(ShiftNotFoundException::new);
        var owner = shift.getCashier();

        return new OpenShiftDto(shift.getId(), UserRefDto.withRole(owner), owner.getId().equals(currentUser.getId()),
                UtcTimestamps.toInstant(shift.getOpenedAt()), shift.getOpeningFloat());
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
}
