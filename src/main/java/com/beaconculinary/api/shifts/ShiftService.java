package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AdminAuthorizationService;
import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.offline.OfflinePending;
import com.beaconculinary.api.endofday.EndOfDayConflictException;
import com.beaconculinary.api.endofday.TradingDayLock;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.sessions.SessionGuard;
import com.beaconculinary.api.tills.CurrentTill;
import com.beaconculinary.api.tills.Till;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.UserRefDto;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

@Service
@AllArgsConstructor
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final ShiftCashCalculator shiftCashCalculator;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AuthService authService;
    private final ShiftMapper shiftMapper;
    private final Clock clock;
    private final TradingDayLock tradingDayLock;
    private final ZReadings zReadings;
    private final CurrentTill currentTill;
    private final OpenShiftConflicts openShiftConflicts;
    private final SessionGuard sessionGuard;
    private final OfflineGrantStore offlineGrants;
    private final OfflinePending offlinePending;

    @Transactional
    public ShiftDto openShift(OpenShiftRequest request) {
        var currentUser = authService.getCurrentUser();
        // O2 R7: an active session.
        sessionGuard.requireActive();
        // O2 R4: a registered till when pos.tills.require-registered is on.
        currentTill.requireRegisteredForPos();
        var till = currentTill.resolve();

        // Reports R3: a shift opens on today's trading day, which may already be closed by a Z.
        // The trading-day lock serialises this with Close Day, so a shift can't slip in while the
        // Z is being built.
        var tradingDay = LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
        tradingDayLock.lock(tradingDay);
        var closed = zReadings.forDay(tradingDay);
        if (closed.isPresent()) {
            throw new EndOfDayConflictException("DAY_CLOSED", "Today's trading day is already closed ("
                    + closed.get().reference() + "). No more shifts can open today.", null, closed.get().zNumber());
        }

        // O2 R3/R6: one open shift per till (the unregistered till counts as one) and one per cashier,
        // ADMIN included: SHIFT_OPEN_ON_OTHER_TILL or SHIFT_ALREADY_OPEN. The trading-day lock above
        // serialises opens, so this check sees every committed shift.
        openShiftConflicts.refuseOpen(currentUser.getId(), till);

        var shift = new Shift();
        shift.setCashier(currentUser);
        shift.setOpeningFloat(request.getOpeningFloat());
        shift.setStatus(ShiftStatus.OPEN);
        shift.setDeviceId(till.deviceId());
        shift.setTillCode(till.code());
        shift.setTillName(till.registered() ? till.name() : null);

        // The unique indexes UX_shifts_open_device and UX_shifts_open_cashier (V107) are the backstop;
        // flush now so a violation surfaces here, not at commit, and explain it the same way.
        try {
            shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException e) {
            openShiftConflicts.refuseOpen(currentUser.getId(), till);
            throw e;
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
        sessionGuard.requireActive();
        // POS Oversight B3: exclusive row lock before anything is read or computed, so an order
        // or adjustment can't attach to this shift between the expected-cash calculation and
        // the close being written (see ShiftRepository.findOpenOnTillWithSharedLock). Taken
        // before the load so the status check below sees any concurrent close.
        shiftRepository.lockForClose(id).orElseThrow(ShiftNotFoundException::new);
        var shift = loadShiftForCaller(id);

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new ShiftAlreadyClosedException();
        }
        // O3: not while this shift's till reports unsynced sales for it (force close stays possible).
        offlinePending.forShift(shift.getId(), shift.getDeviceId()).ifPresent(pending -> {
            throw OfflinePending.shiftCloseRefused(pending);
        });

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

        // UTC, matching opened_at's SYSUTCDATETIME() default (V84 corrected older rows). Shared with the
        // admin force close (O2), so both snapshot the same figures.
        var now = UtcTimestamps.nowUtc(clock);
        ShiftCloses.record(shift, breakdown, request.getCountedCash(), variance, authService.getCurrentUser(), now);
        shiftRepository.save(shift);
        // O3: the shift's offline grants end with it.
        offlineGrants.revokeForShift(shift.getId(), OfflineGrantStore.RevokeReason.SHIFT_CLOSED, now);

        return shiftMapper.toDto(shift);
    }

    public ShiftDto getCurrentShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(ShiftNotFoundException::new);

        return shiftMapper.toDto(shift);
    }

    /** POS Oversight B2, per till since O2: this till's open shift whoever owns it, unlike
     * getCurrentShift, which finds the caller's own on any till. */
    @Transactional(readOnly = true)
    public OpenShiftDto getOpenShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findOpenOnTill(currentTill.deviceId()).orElseThrow(ShiftNotFoundException::new);
        var owner = shift.getCashier();

        return new OpenShiftDto(shift.getId(), UserRefDto.withRole(owner), owner.getId().equals(currentUser.getId()),
                UtcTimestamps.toInstant(shift.getOpenedAt()), shift.getOpeningFloat(),
                shift.getTillCode(), Till.displayName(shift.getDeviceId(), shift.getTillName()));
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
