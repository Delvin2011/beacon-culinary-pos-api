package com.beaconculinary.api.endofday;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.auth.RecentPasswordCheck;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.common.locale.SiteLocale;
import com.beaconculinary.api.shifts.ShiftCashCount;
import com.beaconculinary.api.shifts.ShiftCashCountEvents;
import com.beaconculinary.api.shifts.ShiftNotFoundException;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import com.beaconculinary.api.tills.TillException;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * Reports R3 §3: the manager signs off each closed shift before Close Day. Separate from the
 * variance PIN at shift close. Every sign-off and revoke is a new shift_signoffs row; a revoke
 * needs a note and is refused once the day is closed. Both take the trading-day lock, so neither
 * can race a Close Day.
 */
@Service
@AllArgsConstructor
public class ShiftSignoffService {
    private final ShiftRepository shiftRepository;
    private final ShiftSignoffs signoffs;
    private final ZReadings zReadings;
    private final TradingDayLock tradingDayLock;
    private final AuthService authService;
    private final CloseDaySettings closeDaySettings;
    private final RecentPasswordCheck recentPasswordCheck;
    private final ShiftCashCountEvents cashCountEvents;
    private final Clock clock;
    private final SiteLocale siteLocale;

    public record SignoffDto(long shiftId, boolean signedOff, String by, java.time.Instant at, String note) {
    }

    @Transactional
    public SignoffDto signOff(long shiftId, String note, boolean confirmNoCount) {
        var shift = shiftRepository.findById(shiftId).orElseThrow(ShiftNotFoundException::new);
        var day = tradingDay(shift.getOpenedAt());
        tradingDayLock.lock(day);
        if (!closeDaySettings.covers(day)) {
            throw new EndOfDayConflictException("BEFORE_FIRST_TRADING_DAY", "Shifts before the first trading day aren't "
                    + "signed off (" + closeDaySettings.startsLabel() + ").");
        }
        if (shift.getStatus() != ShiftStatus.CLOSED) {
            throw new EndOfDayConflictException("SHIFT_NOT_CLOSED", "Only a closed shift can be signed off.");
        }
        if (signoffs.isSignedOff(shiftId)) {
            throw new EndOfDayConflictException("ALREADY_SIGNED_OFF", "This shift is already signed off.");
        }
        // O2: a shift force-closed without a count needs a late count first, or a confirmed "no count"
        // with a reason (which this sign-off records); either way it's audited.
        if (shift.getCashCount() == ShiftCashCount.UNCOUNTED) {
            if (!confirmNoCount) {
                throw new TillException(HttpStatus.CONFLICT, "SHIFT_UNCOUNTED", "Shift #" + shiftId
                        + " was force-closed without a cash count. Record the count or confirm there is no count.",
                        Map.of("shiftId", shiftId));
            }
            if (note == null || note.isBlank()) {
                throw new InvalidEndOfDayRequestException("note is required to confirm there is no count.");
            }
            var admin = recentPasswordCheck.require("confirm there is no cash count");
            shift.setCashCount(ShiftCashCount.NO_COUNT_CONFIRMED);
            shiftRepository.save(shift);
            cashCountEvents.record(shiftId, ShiftCashCountEvents.Action.NO_COUNT_CONFIRMED, null, shift.getExpectedCash(),
                    null, null, note.trim(), admin, UtcTimestamps.nowUtc(clock));
        }
        signoffs.record(shiftId, "SIGNED_OFF", authService.getCurrentUser().getId(), blankToNull(note));
        return state(shiftId);
    }

    @Transactional
    public SignoffDto revoke(long shiftId, String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidEndOfDayRequestException("note is required to revoke a sign-off.");
        }
        var shift = shiftRepository.findById(shiftId).orElseThrow(ShiftNotFoundException::new);
        var day = tradingDay(shift.getOpenedAt());
        tradingDayLock.lock(day);
        var z = zReadings.forDay(day);
        if (z.isPresent()) {
            throw new EndOfDayConflictException("DAY_ALREADY_CLOSED",
                    "The day is closed (" + z.get().reference() + "), so its sign-offs can't be revoked.", null, z.get().zNumber());
        }
        if (!signoffs.isSignedOff(shiftId)) {
            throw new EndOfDayConflictException("NOT_SIGNED_OFF", "This shift isn't signed off.");
        }
        signoffs.record(shiftId, "REVOKED", authService.getCurrentUser().getId(), note.trim());
        return state(shiftId);
    }

    private SignoffDto state(long shiftId) {
        var state = signoffs.latest(java.util.List.of(shiftId)).get(shiftId);
        return new SignoffDto(shiftId, state.signedOff(), state.byName(), state.at(), state.note());
    }

    LocalDate tradingDay(java.time.LocalDateTime openedAtUtc) {
        return siteLocale.businessDate(UtcTimestamps.toInstant(openedAtUtc));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
