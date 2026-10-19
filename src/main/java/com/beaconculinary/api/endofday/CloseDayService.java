package com.beaconculinary.api.endofday;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.ReportEnvelope;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportRange;
import com.beaconculinary.api.reports.ReportService;
import com.beaconculinary.api.reports.definitions.ZReadingReport;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * Reports R3 §6: Close Day. Under the trading-day lock (so no shift can open meanwhile and a
 * second Close Day waits, then finds the day closed), refuses with every blocker at once, or
 * builds the Z from the same builder as the X Reading, gives it the next gap-free number and
 * stores the whole envelope as its snapshot, all in one transaction.
 */
@Service
@AllArgsConstructor
public class CloseDayService {
    private final TradingDayLock tradingDayLock;
    private final DayCloseRules dayCloseRules;
    private final ZReadings zReadings;
    private final EndOfDayStatements statements;
    private final ReportFacts facts;
    private final ReportService reportService;
    private final ZReadingReport zReadingReport;
    private final AuthService authService;
    private final Clock clock;

    @Transactional
    public ReportEnvelope close(LocalDate tradingDay) {
        tradingDayLock.lock(tradingDay);
        var blockers = dayCloseRules.blockers(tradingDay);
        if (!blockers.isEmpty()) {
            throw new EndOfDayConflictException("CLOSE_DAY_BLOCKED", "This day can't be closed yet.", blockers, null);
        }

        var closedBy = authService.getCurrentUser();
        var zNumber = zReadings.nextNumber();
        var closedAt = clock.instant();
        var result = statements.dayReading(tradingDay, new EndOfDayStatements.ZContext(zNumber, closedBy.getName(), closedAt));
        var envelope = reportService.wrap(zReadingReport, ReportParams.of(Map.of("zNumber", (long) zNumber)), result);

        var sales = facts.salesTotals(new ReportRange(tradingDay, tradingDay));
        zReadings.insert(zNumber, tradingDay, closedBy.getId(), closedAt, ZReadingReport.Z_DEFINITION_VERSION,
                sales.grossSales(), sales.netSales(), zReadingReport.toSnapshot(envelope));
        return envelope;
    }
}
