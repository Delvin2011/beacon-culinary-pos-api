package com.beaconculinary.api.endofday;

import com.beaconculinary.api.common.ClockConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Reports R3 §6: what stops a trading day from being closed. Every blocker is returned at once.
 * The first trading day (CloseDaySettings) is where Z numbering starts: earlier days
 * can't be closed and don't block later ones. Unset, every earlier day with shifts must be
 * closed first, so Z numbers stay in date order.
 */
@Component
public class DayCloseRules {
    private final NamedParameterJdbcTemplate jdbc;
    private final ShiftSignoffs signoffs;
    private final ZReadings zReadings;
    private final Clock clock;
    private final LocalDate firstTradingDay;

    public DayCloseRules(NamedParameterJdbcTemplate jdbc, ShiftSignoffs signoffs, ZReadings zReadings, Clock clock,
                         CloseDaySettings settings) {
        this.jdbc = jdbc;
        this.signoffs = signoffs;
        this.zReadings = zReadings;
        this.clock = clock;
        this.firstTradingDay = settings.firstTradingDay();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Blocker(String code, String message, Long shiftId, LocalDate date, Integer zNumber) {
    }

    /** A shift that opened on a trading day, with its owner. */
    public record DayShift(long shiftId, String status, String ownerName) {
        public boolean open() {
            return "OPEN".equals(status);
        }

        public String label() {
            return "Shift #" + shiftId + " (" + ownerName + ")";
        }
    }

    public List<DayShift> shifts(LocalDate tradingDay) {
        return jdbc.query("""
                SELECT sf.shift_id, s.status, u.name AS owner_name
                FROM v_shift_facts sf
                JOIN shifts s ON s.id = sf.shift_id
                JOIN users u ON u.id = s.cashier_id
                WHERE sf.trading_day = :day
                ORDER BY sf.opened_at_utc, sf.shift_id""",
                new MapSqlParameterSource("day", java.sql.Date.valueOf(tradingDay)),
                (rs, i) -> new DayShift(rs.getLong("shift_id"), rs.getString("status"), rs.getString("owner_name")));
    }

    public List<Blocker> blockers(LocalDate tradingDay) {
        var blockers = new ArrayList<Blocker>();
        var closed = zReadings.forDay(tradingDay);
        if (closed.isPresent()) {
            var z = closed.get();
            blockers.add(new Blocker("DAY_ALREADY_CLOSED", "This day is already closed (" + z.reference() + ").",
                    null, tradingDay, z.zNumber()));
            return blockers;
        }
        var today = LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
        if (tradingDay.isAfter(today)) {
            blockers.add(new Blocker("DAY_IN_FUTURE", "This day hasn't happened yet.", null, tradingDay, null));
            return blockers;
        }
        if (firstTradingDay != null && tradingDay.isBefore(firstTradingDay)) {
            blockers.add(new Blocker("BEFORE_FIRST_TRADING_DAY",
                    "Days before " + firstTradingDay + " are not closed with a Z reading.", null, tradingDay, null));
            return blockers;
        }

        var shifts = shifts(tradingDay);
        if (shifts.isEmpty()) {
            blockers.add(new Blocker("NO_SHIFTS_FOR_DAY", "No shift was opened on this day.", null, tradingDay, null));
        }
        var states = signoffs.latest(shifts.stream().map(DayShift::shiftId).toList());
        for (var shift : shifts) {
            if (shift.open()) {
                blockers.add(new Blocker("SHIFT_OPEN", shift.label() + " is still open.", shift.shiftId(), null, null));
            }
        }
        for (var shift : shifts) {
            var state = states.get(shift.shiftId());
            if (state == null || !state.signedOff()) {
                blockers.add(new Blocker("SHIFT_NOT_SIGNED_OFF", shift.label() + " is not signed off.", shift.shiftId(),
                        null, null));
            }
        }
        for (var earlier : unclosedEarlierDays(tradingDay)) {
            blockers.add(new Blocker("PREVIOUS_DAY_NOT_CLOSED", "The earlier trading day " + earlier + " is not closed yet.",
                    null, earlier, null));
        }
        return blockers;
    }

    /** Earlier trading days with shifts (from the first trading day, when set) that have no Z. */
    private List<LocalDate> unclosedEarlierDays(LocalDate tradingDay) {
        var params = new MapSqlParameterSource("day", java.sql.Date.valueOf(tradingDay));
        var sql = "SELECT DISTINCT trading_day FROM v_shift_facts WHERE trading_day < :day";
        if (firstTradingDay != null) {
            sql += " AND trading_day >= :first";
            params.addValue("first", java.sql.Date.valueOf(firstTradingDay));
        }
        var closed = new HashSet<>(zReadings.closedDaysFrom(firstTradingDay));
        return jdbc.queryForList(sql + " ORDER BY trading_day", params, java.sql.Date.class).stream()
                .map(java.sql.Date::toLocalDate)
                .filter(day -> !closed.contains(day))
                .toList();
    }
}
