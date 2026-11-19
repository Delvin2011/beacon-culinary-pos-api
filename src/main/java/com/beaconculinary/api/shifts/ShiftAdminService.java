package com.beaconculinary.api.shifts;

import com.beaconculinary.api.auth.RecentPasswordCheck;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.common.locale.SiteLocale;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.endofday.EndOfDayConflictException;
import com.beaconculinary.api.endofday.ShiftSignoffs;
import com.beaconculinary.api.endofday.TradingDayLock;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionStore;
import com.beaconculinary.api.tills.TillException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * O2 R11: an ADMIN force-closes an abandoned shift, and records its cash count later when it was
 * closed without one (section 10 of docs/2026-11-17-o2-tills-shifts-api-contract.md). Both need a
 * recent password sign-in; the admin's sign-in is the variance approval (no manager-PIN token).
 * Every action is written to the insert-only shift_cash_count_events.
 */
@Service
public class ShiftAdminService {
    private final ShiftRepository shiftRepository;
    private final ShiftCashCalculator shiftCashCalculator;
    private final ShiftMapper shiftMapper;
    private final ShiftCashCountEvents events;
    private final SessionStore sessionStore;
    private final RecentPasswordCheck recentPasswordCheck;
    private final TradingDayLock tradingDayLock;
    private final ZReadings zReadings;
    private final ShiftSignoffs signoffs;
    private final OfflineGrantStore offlineGrants;
    private final Clock clock;
    private final SiteLocale siteLocale;

    public ShiftAdminService(ShiftRepository shiftRepository, ShiftCashCalculator shiftCashCalculator, ShiftMapper shiftMapper,
                             ShiftCashCountEvents events, SessionStore sessionStore, RecentPasswordCheck recentPasswordCheck,
                             TradingDayLock tradingDayLock, ZReadings zReadings, ShiftSignoffs signoffs,
                             OfflineGrantStore offlineGrants, Clock clock, SiteLocale siteLocale) {
        this.shiftRepository = shiftRepository;
        this.shiftCashCalculator = shiftCashCalculator;
        this.shiftMapper = shiftMapper;
        this.events = events;
        this.sessionStore = sessionStore;
        this.recentPasswordCheck = recentPasswordCheck;
        this.tradingDayLock = tradingDayLock;
        this.zReadings = zReadings;
        this.signoffs = signoffs;
        this.offlineGrants = offlineGrants;
        this.clock = clock;
        this.siteLocale = siteLocale;
    }

    /**
     * Closes a shift on any till, as a normal close does (same calculation, same lock against in-flight
     * sales), with or without a count. The owner's POS sessions end (SHIFT_FORCE_CLOSED).
     */
    @Transactional
    public ShiftDto forceClose(long id, ShiftAdminRequests.ForceClose request) {
        var admin = recentPasswordCheck.require("close another cashier's shift");
        shiftRepository.lockForClose(id).orElseThrow(ShiftNotFoundException::new);
        var shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new ShiftAlreadyClosedException();
        }
        var reason = request.getReason().trim();
        var breakdown = shiftCashCalculator.compute(shift.getId(), shift.getOpeningFloat());
        var counted = request.getCountedCash();
        BigDecimal variance = null;
        if (counted != null) {
            variance = counted.subtract(breakdown.expectedCash());
            if (variance.signum() != 0) {
                requireReasonCode(request.getVarianceReasonCode(), breakdown.expectedCash());
                shift.setVarianceReasonCode(request.getVarianceReasonCode());
                shift.setVarianceNote(reason);
                shift.setVarianceAuthorizedBy(admin);
            }
        }
        var now = UtcTimestamps.nowUtc(clock);
        ShiftCloses.record(shift, breakdown, counted, variance, admin, now);
        shift.setForceClosed(true);
        shift.setForceCloseReason(reason);
        shift.setCashCount(counted == null ? ShiftCashCount.UNCOUNTED : ShiftCashCount.COUNTED);
        shiftRepository.save(shift);

        events.record(shift.getId(), ShiftCashCountEvents.Action.FORCE_CLOSED, counted, breakdown.expectedCash(), variance,
                shift.getVarianceReasonCode(), reason, admin, now);
        sessionStore.endActivePosOfUser(shift.getCashier().getId(), SessionEndReason.SHIFT_FORCE_CLOSED, now);
        offlineGrants.revokeForShift(shift.getId(), OfflineGrantStore.RevokeReason.SHIFT_FORCE_CLOSED, now);
        return shiftMapper.toDto(shift);
    }

    /**
     * Records the count of a shift force-closed without one (UNCOUNTED, or NO_COUNT_CONFIRMED whose
     * sign-off was revoked). Variance is against the expected cash snapshotted at close. Doesn't sign
     * the shift off.
     */
    @Transactional
    public ShiftDto lateCount(long id, ShiftAdminRequests.LateCount request) {
        var admin = recentPasswordCheck.require("record a cash count");
        var shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        var day = siteLocale.businessDate(UtcTimestamps.toInstant(shift.getOpenedAt()));
        tradingDayLock.lock(day);
        var z = zReadings.forDay(day);
        if (z.isPresent()) {
            throw new EndOfDayConflictException("DAY_CLOSED", "The day is closed (" + z.get().reference()
                    + "), so this shift's count can't change.", null, z.get().zNumber());
        }
        if (signoffs.isSignedOff(id)) {
            throw new EndOfDayConflictException("ALREADY_SIGNED_OFF", "This shift is already signed off. Revoke the sign-off first.");
        }
        var cashCount = shift.getCashCount();
        if (shift.getStatus() != ShiftStatus.CLOSED
                || (cashCount != ShiftCashCount.UNCOUNTED && cashCount != ShiftCashCount.NO_COUNT_CONFIRMED)) {
            throw new TillException(HttpStatus.CONFLICT, "SHIFT_NOT_UNCOUNTED",
                    "Only a shift closed without a cash count can be counted later.", null);
        }
        var note = request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim();
        var expected = shift.getExpectedCash();
        var variance = request.getCountedCash().subtract(expected);
        if (variance.signum() != 0) {
            requireReasonCode(request.getVarianceReasonCode(), expected);
            if (request.getVarianceReasonCode() == ShiftVarianceReasonCode.OTHER && note == null) {
                throw new ShiftFieldException(Map.of("note", "note is required when varianceReasonCode is OTHER."));
            }
            shift.setVarianceReasonCode(request.getVarianceReasonCode());
        } else {
            shift.setVarianceReasonCode(null);
        }
        shift.setVarianceNote(note);
        shift.setVarianceAuthorizedBy(admin);
        shift.setClosingCash(request.getCountedCash());
        shift.setVariance(variance);
        shift.setCashCount(ShiftCashCount.COUNTED_LATE);
        shiftRepository.save(shift);

        events.record(shift.getId(), ShiftCashCountEvents.Action.LATE_COUNT, request.getCountedCash(), expected, variance,
                shift.getVarianceReasonCode(), note, admin, UtcTimestamps.nowUtc(clock));
        return shiftMapper.toDto(shift);
    }

    private static void requireReasonCode(ShiftVarianceReasonCode code, BigDecimal expected) {
        if (code == null) {
            throw new ShiftFieldException(Map.of("varianceReasonCode",
                    "varianceReasonCode is required when countedCash differs from expected cash (" + expected.toPlainString() + ")."));
        }
    }

    /** A 400 field map, like the @Valid errors. */
    public static class ShiftFieldException extends RuntimeException {
        private final transient Map<String, String> errors;

        public ShiftFieldException(Map<String, String> errors) {
            super(errors.toString());
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }
}
