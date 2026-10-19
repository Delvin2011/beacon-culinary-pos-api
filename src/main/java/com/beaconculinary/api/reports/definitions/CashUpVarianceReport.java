package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.endofday.CloseDaySettings;
import com.beaconculinary.api.endofday.ShiftSignoffs;
import com.beaconculinary.api.reports.CheckStatus;
import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.ParamType;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.reports.RowAction;
import com.beaconculinary.api.reports.ShiftCashFigures;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reports R1 §2.3: one row per shift, by the trading day it opened. */
@Component
@AllArgsConstructor
public class CashUpVarianceReport implements ReportDefinition {
    private static final Map<String, String> STATUS_TONES = Map.of(
            "OPEN", "positive",
            "CLOSED", "neutral",
            "OVERDUE", "negative");

    private final ReportFacts facts;
    private final ShiftCashFigures shiftCashFigures;
    private final Clock clock;
    private final ShiftSignoffs signoffs;
    private final CloseDaySettings closeDaySettings;

    @Override
    public String key() {
        return "cash-up-variance";
    }

    @Override
    public String title() {
        return "Cash-up & Variance";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.DAILY_STATEMENTS;
    }

    @Override
    public String description() {
        return "Every shift's expected and counted cash, and any variance.";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                new ReportParamSpec("ownerId", "Owner", ParamType.USER, null, null),
                new ReportParamSpec("varianceOnly", "Variance only", ParamType.BOOLEAN, "false", null),
                new ReportParamSpec("status", "Status", ParamType.ENUM, null, List.of("OPEN", "CLOSED")),
                ReportParamSpec.choice("signoff", "Sign-off", "ALL", "UNSIGNED"));
    }

    /** 2: complimentary meals and their list value per shift. Cash figures are unchanged.
     * 3: who signed each shift off and when, the UNSIGNED filter, and row actions (sign off,
     * open the shift's Cashier Reading). */
    @Override
    public int definitionVersion() {
        return 3;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "One row per shift, on the trading day the shift opened.",
                "Expected cash is the opening float plus cash sales, minus cash refunds processed.",
                "Cash refunds processed is cash handed back by voids and refunds during the shift. Discounts given at sale "
                        + "are not included, because no cash was involved.",
                "A closed shift shows the figures stored when it was closed. An open shift's expected cash is live and "
                        + "changes as it trades. Overdue means a shift is still open from an earlier day.",
                "Variance is counted cash minus expected cash: negative means the drawer was short, positive means over.",
                "Counted cash and variance totals cover closed shifts only.",
                "Complimentary meals are counted in the shift they were rung in.",
                SalesSummaryReport.COMPLIMENTARY_NOTE);
    }

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        var allShifts = facts.shifts(range, params.userId("ownerId"), params.string("status"), params.bool("varianceOnly"));
        var states = signoffs.latest(allShifts.stream().map(ReportFacts.ShiftFacts::shiftId).toList());
        // UNSIGNED: closed shifts without a current sign-off (an open shift can't be signed off yet),
        // from the first trading day on (earlier shifts are outside sign-off).
        var shifts = "UNSIGNED".equals(params.string("signoff"))
                ? allShifts.stream().filter(shift -> !shift.open() && !signedOff(states, shift.shiftId())
                        && closeDaySettings.covers(shift.tradingDay())).toList()
                : allShifts;
        var figures = shiftCashFigures.figures(shifts);
        var complimentaryByShift = facts.complimentaryByShift(range);
        var today = LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);

        var columns = List.of(
                ReportColumn.of("tradingDay", "Trading day", ColumnType.DATE, ColumnTotal.LABEL),
                ReportColumn.of("shiftId", "Shift #", ColumnType.INT, ColumnTotal.NONE),
                ReportColumn.of("owner", "Owner", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("openedAt", "Opened", ColumnType.DATETIME, ColumnTotal.NONE),
                ReportColumn.of("closedAt", "Closed", ColumnType.DATETIME, ColumnTotal.NONE),
                ReportColumn.of("openingFloat", "Opening float", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("cashSales", "Cash sales", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("cashRefunds", "Cash refunds processed", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("expectedCash", "Expected cash", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("countedCash", "Counted cash", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("variance", "Variance", ColumnType.VARIANCE, ColumnTotal.SUM),
                ReportColumn.of("reason", "Reason", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("authorisedBy", "Authorised by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("closedBy", "Closed by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("complimentaryMeals", "Complimentary meals", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("complimentaryValue", "Complimentary value", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("signedOffBy", "Signed off by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("signedOffAt", "Signed off at", ColumnType.DATETIME, ColumnTotal.NONE),
                new ReportColumn("status", "Status", ColumnType.STATUS, ColumnTotal.NONE, STATUS_TONES));

        var rows = new ArrayList<Map<String, Object>>();
        var floatTotal = BigDecimal.ZERO;
        var cashSalesTotal = BigDecimal.ZERO;
        var cashRefundsTotal = BigDecimal.ZERO;
        var expectedTotal = BigDecimal.ZERO;
        BigDecimal countedTotal = null;
        BigDecimal varianceTotal = null;
        long withVariance = 0;
        long arithmeticFailures = 0;
        long varianceFailures = 0;
        long legacyNotChecked = 0;
        long complimentaryMeals = 0;
        var complimentaryValue = BigDecimal.ZERO;

        for (var shift : shifts) {
            var cash = figures.get(shift.shiftId());
            var open = shift.open();

            var row = new LinkedHashMap<String, Object>();
            row.put("tradingDay", shift.tradingDay());
            row.put("shiftId", shift.shiftId());
            row.put("owner", shift.ownerName());
            row.put("openedAt", UtcTimestamps.toInstant(shift.openedAtUtc()));
            row.put("closedAt", open ? null : UtcTimestamps.toInstant(shift.closedAtUtc()));
            row.put("openingFloat", ReportValues.money(shift.openingFloat()));
            row.put("cashSales", ReportValues.money(cash.cashSales()));
            row.put("cashRefunds", ReportValues.money(cash.cashRefunds()));
            row.put("expectedCash", ReportValues.money(cash.expectedCash()));
            row.put("countedCash", open ? null : ReportValues.money(shift.closingCash()));
            row.put("variance", open ? null : ReportValues.money(shift.variance()));
            row.put("reason", humanize(shift.varianceReasonCode()));
            row.put("authorisedBy", shift.varianceAuthorizedByName());
            row.put("closedBy", open ? null : shift.closedByName());
            var complimentary = complimentaryByShift.getOrDefault(shift.shiftId(), ReportFacts.ComplimentaryFacts.none(null));
            row.put("complimentaryMeals", complimentary.orders());
            row.put("complimentaryValue", ReportValues.money(complimentary.value()));
            var state = states.get(shift.shiftId());
            var signed = state != null && state.signedOff();
            row.put("signedOffBy", signed ? state.byName() : null);
            row.put("signedOffAt", signed ? state.at() : null);
            row.put("status", !open ? "CLOSED" : shift.tradingDay().isBefore(today) ? "OVERDUE" : "OPEN");
            var actions = new ArrayList<RowAction>();
            if (!open && !signed && closeDaySettings.covers(shift.tradingDay())) {
                actions.add(RowAction.post("SIGN_OFF", "Sign off", "/admin/shifts/" + shift.shiftId() + "/signoff",
                        "Sign off shift #" + shift.shiftId() + " (" + shift.ownerName() + ")?", RowAction.OnSuccess.RELOAD));
            }
            actions.add(RowAction.openReport("OPEN_READING", "Cashier reading", "cashier-reading",
                    Map.of("shiftId", shift.shiftId())));
            row.put(RowAction.ROW_KEY, actions);
            rows.add(row);
            complimentaryMeals += complimentary.orders();
            complimentaryValue = complimentaryValue.add(complimentary.value());

            floatTotal = floatTotal.add(shift.openingFloat());
            cashSalesTotal = cashSalesTotal.add(cash.cashSales());
            cashRefundsTotal = cashRefundsTotal.add(cash.cashRefunds());
            expectedTotal = expectedTotal.add(cash.expectedCash());

            if (shift.legacy()) {
                legacyNotChecked++;
            } else if (shift.openingFloat().add(cash.cashSales()).subtract(cash.cashRefunds()).compareTo(cash.expectedCash()) != 0) {
                arithmeticFailures++;
            }
            // Very old closes may lack counted cash or variance; they show "—" and are skipped here.
            if (!open && shift.closingCash() != null && shift.variance() != null) {
                countedTotal = (countedTotal == null ? BigDecimal.ZERO : countedTotal).add(shift.closingCash());
                varianceTotal = (varianceTotal == null ? BigDecimal.ZERO : varianceTotal).add(shift.variance());
                if (shift.variance().signum() != 0) {
                    withVariance++;
                }
                if (shift.expectedCash() == null
                        || shift.closingCash().subtract(shift.expectedCash()).compareTo(shift.variance()) != 0) {
                    varianceFailures++;
                }
            }
        }

        var totals = new LinkedHashMap<String, Object>();
        totals.put("tradingDay", "Total");
        totals.put("owner", shifts.size() + (shifts.size() == 1 ? " shift" : " shifts"));
        totals.put("openingFloat", ReportValues.money(floatTotal));
        totals.put("cashSales", ReportValues.money(cashSalesTotal));
        totals.put("cashRefunds", ReportValues.money(cashRefundsTotal));
        totals.put("expectedCash", ReportValues.money(expectedTotal));
        totals.put("countedCash", countedTotal == null ? null : ReportValues.money(countedTotal));
        totals.put("variance", varianceTotal == null ? null : ReportValues.money(varianceTotal));
        totals.put("reason", withVariance + " with a variance");
        totals.put("complimentaryMeals", complimentaryMeals);
        totals.put("complimentaryValue", ReportValues.money(complimentaryValue));

        var expectedCheck = ReportCheck.failures("expected-arithmetic",
                "Opening float + cash sales − cash refunds equals expected cash, for every shift", arithmeticFailures,
                arithmeticFailures == 0 ? null : arithmeticFailures + " " + (arithmeticFailures == 1 ? "shift doesn't" : "shifts don't") + " add up.");
        if (legacyNotChecked > 0 && expectedCheck.status() == CheckStatus.PASS) {
            expectedCheck = expectedCheck.withStatus(CheckStatus.WARN, legacyNotChecked + " legacy "
                    + (legacyNotChecked == 1 ? "shift" : "shifts") + " not checked: closed before the cash breakdown was stored.");
        }

        var reconciliation = shiftCashFigures.reconcile(range);
        var openShifts = reconciliation.openShifts();
        var openCheck = ReportCheck.count("open-shifts", "No shift in the range is still open", openShifts,
                openShifts == 0 ? CheckStatus.PASS : CheckStatus.WARN,
                openShifts == 0 ? null : openShifts + (openShifts == 1 ? " shift is" : " shifts are")
                        + " still open, so expected cash is live.");

        var checks = List.of(
                expectedCheck,
                ReportCheck.failures("variance-arithmetic", "Counted cash − expected cash equals variance, for closed shifts",
                        varianceFailures, varianceFailures == 0 ? null : varianceFailures + " closed "
                                + (varianceFailures == 1 ? "shift doesn't" : "shifts don't") + " add up."),
                shiftCashFigures.check("matches-tender", "Cash-ups (expected cash − opening float) equal Tender Summary's net cash movement",
                        reconciliation, true),
                openCheck);

        return new ReportResult(columns, rows, totals, checks);
    }

    private static boolean signedOff(Map<Long, ShiftSignoffs.State> states, long shiftId) {
        var state = states.get(shiftId);
        return state != null && state.signedOff();
    }

    /** CASH_COUNTING_ERROR → "Cash counting error". */
    private static String humanize(String code) {
        if (code == null) {
            return null;
        }
        var text = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
