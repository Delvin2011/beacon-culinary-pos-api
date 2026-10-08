package com.beaconculinary.api.reports;

import com.beaconculinary.api.reports.ReportFacts.ShiftFacts;
import com.beaconculinary.api.shifts.ShiftCashCalculator;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports R1 §1.5: a shift's cash sales, cash refunds processed and expected cash, never
 * re-derived here. Closed shifts use the parts snapshotted at close; open shifts use the live
 * ShiftCashCalculator; legacy shifts (closed before V82) show recalculated parts beside their
 * stored expected cash. Also reconciles the drawers against the order facts, which both Tender
 * Summary (cash-matches-cashups) and Cash-up & Variance (matches-tender) report.
 */
@Component
@AllArgsConstructor
public class ShiftCashFigures {
    private final ShiftCashCalculator shiftCashCalculator;
    private final ReportFacts facts;

    public record Figures(BigDecimal cashSales, BigDecimal cashRefunds, BigDecimal expectedCash) {
    }

    /** netCashMovement and expectedMinusFloat cover only non-legacy shifts, so both sides of the
     * comparison describe the same drawers. */
    public record Reconciliation(BigDecimal netCashMovement, BigDecimal expectedMinusFloat, long legacyExcluded, long openShifts) {
    }

    public Map<Long, Figures> figures(List<ShiftFacts> shifts) {
        var recalculate = new HashMap<Long, BigDecimal>();
        for (var shift : shifts) {
            if (shift.open() || shift.legacy()) {
                recalculate.put(shift.shiftId(), shift.openingFloat());
            }
        }
        var computed = shiftCashCalculator.computeAll(recalculate);

        var figures = new HashMap<Long, Figures>();
        for (var shift : shifts) {
            var live = computed.get(shift.shiftId());
            if (shift.open()) {
                figures.put(shift.shiftId(), new Figures(live.cashSales(), live.cashRefunds(), live.expectedCash()));
            } else if (shift.legacy()) {
                var stored = shift.expectedCash() != null ? shift.expectedCash() : live.expectedCash();
                figures.put(shift.shiftId(), new Figures(live.cashSales(), live.cashRefunds(), stored));
            } else {
                figures.put(shift.shiftId(), new Figures(shift.cashSalesAtClose(), shift.cashRefundsAtClose(), shift.expectedCash()));
            }
        }
        return figures;
    }

    /** Σ(expected − float) over the shifts opened in the range, against the net cash movement
     * the order facts give for those same shifts. Unaffected by any report filters. */
    public Reconciliation reconcile(ReportRange range) {
        var shifts = facts.shifts(range, null, null, false);
        var figures = figures(shifts);
        var movement = facts.netCashMovementByShift(range);

        var net = BigDecimal.ZERO;
        var expectedMinusFloat = BigDecimal.ZERO;
        long legacy = 0;
        long open = 0;
        for (var shift : shifts) {
            if (shift.open()) {
                open++;
            }
            if (shift.legacy()) {
                legacy++;
                continue;
            }
            net = net.add(movement.getOrDefault(shift.shiftId(), BigDecimal.ZERO));
            expectedMinusFloat = expectedMinusFloat.add(figures.get(shift.shiftId()).expectedCash().subtract(shift.openingFloat()));
        }
        return new Reconciliation(ReportValues.money(net), ReportValues.money(expectedMinusFloat), legacy, open);
    }

    /** The check both reports show, from either side's point of view. */
    public ReportCheck check(String key, String label, Reconciliation reconciliation, boolean cashupsAreActual) {
        var check = cashupsAreActual
                ? ReportCheck.compare(key, label, reconciliation.netCashMovement(), reconciliation.expectedMinusFloat(), null)
                : ReportCheck.compare(key, label, reconciliation.expectedMinusFloat(), reconciliation.netCashMovement(), null);
        if (check.status() == CheckStatus.PASS && reconciliation.legacyExcluded() > 0) {
            return check.withStatus(CheckStatus.WARN, reconciliation.legacyExcluded() + " legacy "
                    + plural(reconciliation.legacyExcluded(), "shift", "shifts")
                    + " excluded: closed before the cash breakdown was stored.");
        }
        if (check.status() == CheckStatus.FAIL && reconciliation.legacyExcluded() > 0) {
            return check.withStatus(CheckStatus.FAIL, reconciliation.legacyExcluded() + " legacy "
                    + plural(reconciliation.legacyExcluded(), "shift", "shifts") + " excluded from both sides.");
        }
        return check;
    }

    static String plural(long count, String one, String many) {
        return count == 1 ? one : many;
    }
}
