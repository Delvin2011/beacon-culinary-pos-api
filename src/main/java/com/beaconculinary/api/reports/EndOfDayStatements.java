package com.beaconculinary.api.reports;

import com.beaconculinary.api.endofday.CloseDaySettings;
import com.beaconculinary.api.endofday.DayCloseRules;
import com.beaconculinary.api.endofday.ShiftSignoffs;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.reports.ReportFacts.ShiftFacts;
import com.beaconculinary.api.reports.definitions.CashUpVarianceReport;
import com.beaconculinary.api.reports.definitions.SalesSummaryReport;
import com.beaconculinary.api.reports.definitions.TenderSummaryReport;
import com.beaconculinary.api.shifts.ShiftVarianceReasonCode;
import com.beaconculinary.api.common.UtcTimestamps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports R3: builds the end-of-day statements. The X Reading (a trading day so far) and the Z
 * Reading (the same day, closed and numbered) come from one builder, so a Z is exactly the X
 * at the moment of closing, plus its sign-offs. The Cashier Reading covers one shift (the
 * drawer, not a person). Everything composes the R1 definitions; nothing re-derives a rule.
 */
@Component
public class EndOfDayStatements {
    public static final String X_TITLE = "X Reading";
    public static final String Z_TITLE = "Z Reading";
    public static final String CASHIER_TITLE = "Cashier Reading";
    /** Sign-off state of a shift before the first trading day. */
    private static final String NOT_REQUIRED = "Not required";
    private static final Map<String, String> ADJUSTMENT_TONES = Map.of(
            "VOID", "negative", "REFUND", "warning", "EXTRAS_REMOVED", "warning", "DISCOUNT", "neutral");

    private final ReportFacts facts;
    private final EndOfDayFacts endOfDayFacts;
    private final ShiftCashFigures shiftCashFigures;
    private final ShiftSignoffs signoffs;
    private final ZReadings zReadings;
    private final DayCloseRules dayCloseRules;
    private final CloseDaySettings closeDaySettings;
    private final SalesSummaryReport salesSummary;
    private final TenderSummaryReport tenderSummary;
    private final CashUpVarianceReport cashUp;
    private final Clock clock;
    private final String businessName;

    public EndOfDayStatements(ReportFacts facts, EndOfDayFacts endOfDayFacts, ShiftCashFigures shiftCashFigures,
                              ShiftSignoffs signoffs, ZReadings zReadings, DayCloseRules dayCloseRules,
                              CloseDaySettings closeDaySettings,
                              SalesSummaryReport salesSummary, TenderSummaryReport tenderSummary,
                              CashUpVarianceReport cashUp, Clock clock,
                              @Value("${app.business.name:Beacon Culinary}") String businessName) {
        this.facts = facts;
        this.endOfDayFacts = endOfDayFacts;
        this.shiftCashFigures = shiftCashFigures;
        this.signoffs = signoffs;
        this.zReadings = zReadings;
        this.dayCloseRules = dayCloseRules;
        this.closeDaySettings = closeDaySettings;
        this.salesSummary = salesSummary;
        this.tenderSummary = tenderSummary;
        this.cashUp = cashUp;
        this.clock = clock;
        this.businessName = businessName;
    }

    /** Set when building the Z for a day being closed: its number and who closed it, when. */
    public record ZContext(int zNumber, String closedByName, Instant closedAt) {
    }

    // ---------------------------------------------------------------------------------------
    // X / Z reading: one trading day, every shift that opened on it.

    public ReportResult dayReading(LocalDate tradingDay, ZContext z) {
        var range = new ReportRange(tradingDay, tradingDay);
        var sales = facts.salesTotals(range);
        var collected = sumCollected(facts.collected(range, GroupBy.DAY));
        var paidOut = sumPaidOut(facts.paidOut(range, GroupBy.DAY));
        var complimentary = facts.complimentaryTotals(range);
        var byReason = endOfDayFacts.complimentaryByReason(range);
        var shifts = facts.shifts(range, null, null, false);
        var figures = shiftCashFigures.figures(shifts);
        var states = signoffs.latest(shifts.stream().map(ShiftFacts::shiftId).toList());
        var takings = endOfDayFacts.takingsByShift(range);

        var sections = new ArrayList<Map<String, Object>>();
        sections.add(dayHeader(tradingDay, z));
        sections.add(salesSection(sales));
        sections.addAll(complimentarySections(complimentary, byReason));
        sections.add(Sections.keyValue("tender", "Tender collected", List.of(
                Sections.item("Cash", ColumnType.MONEY, money(collected.cash())),
                Sections.item("Card", ColumnType.MONEY, money(collected.card())),
                Sections.item("Account", ColumnType.MONEY, money(collected.account())),
                Sections.total("Total collected", ColumnType.MONEY, money(collected.collected())))));
        sections.add(Sections.keyValue("paidOut", "Paid out", List.of(
                Sections.item("Cash paid out", ColumnType.MONEY, money(paidOut.cashPaidOut())),
                Sections.item("Account credits", ColumnType.MONEY, money(paidOut.accountCredits())))));

        var netCashMovement = collected.cash().subtract(paidOut.cashPaidOut());
        sections.add(cashSection(shifts, figures, netCashMovement));
        var inProcess = closeDaySettings.covers(tradingDay);
        sections.add(shiftsTable(shifts, figures, states, inProcess));

        var readings = readingsVsX(shifts, takings, collected);
        sections.add(readings.section());
        if (z != null) {
            sections.add(signoffsTable(shifts, states));
        }
        sections.add(Sections.signatures("signatures", "Manager"));

        var checks = new ArrayList<ReportCheck>();
        checks.add(readings.check());
        checks.add(agreesWithReports(tradingDay, sales, collected, netCashMovement));
        var open = shifts.stream().filter(ShiftFacts::open).count();
        if (open > 0) {
            checks.add(ReportCheck.count("open-shifts", "Every shift of the day is closed", open, CheckStatus.WARN,
                    open + (open == 1 ? " shift is" : " shifts are") + " still open, so figures are live."));
        }
        // Days before the first trading day aren't signed off, so there's nothing to warn about.
        var unsigned = shifts.stream().filter(shift -> !signedOff(states, shift.shiftId())).count();
        if (inProcess && unsigned > 0) {
            checks.add(ReportCheck.count("unsigned-shifts", "Every shift of the day is signed off", unsigned, CheckStatus.WARN,
                    unsigned + (unsigned == 1 ? " shift is" : " shifts are") + " not yet signed off."));
        }

        if (z != null) {
            return ReportResult.statement(new ReportResult.Statement(Z_TITLE, ZReadings.Z.reference(z.zNumber()), false),
                    sections, checks, List.of());
        }
        return ReportResult.statement(new ReportResult.Statement(X_TITLE, null, false), sections, checks,
                List.of(closeDayAction(tradingDay)));
    }

    private Map<String, Object> dayHeader(LocalDate tradingDay, ZContext z) {
        var items = Sections.items();
        items.add(Sections.item("Business", ColumnType.TEXT, businessName));
        items.add(Sections.item("Report", ColumnType.TEXT, z == null ? X_TITLE : Z_TITLE));
        if (z != null) {
            items.add(Sections.item("Z number", ColumnType.TEXT, ZReadings.Z.reference(z.zNumber())));
        }
        items.add(Sections.item("Trading day", ColumnType.DATE, tradingDay));
        if (z != null) {
            items.add(Sections.item("Closed by", ColumnType.TEXT, z.closedByName()));
            items.add(Sections.item("Closed at", ColumnType.DATETIME, z.closedAt()));
        } else {
            items.add(Sections.item("Generated at", ColumnType.DATETIME, clock.instant()));
            var closed = zReadings.forDay(tradingDay);
            items.add(Sections.item("Day status", ColumnType.TEXT, closed.map(c -> "Closed, " + c.reference()).orElse("Open")));
            // Where Z numbering starts, so it's clear why an earlier day can't be closed.
            var firstTradingDay = closeDaySettings.firstTradingDay();
            if (firstTradingDay != null) {
                items.add(Sections.item("Z numbering starts", ColumnType.DATE, firstTradingDay));
            }
        }
        return Sections.keyValue("header", null, items);
    }

    private static Map<String, Object> salesSection(ReportFacts.SalesFacts sales) {
        var items = Sections.items();
        items.add(Sections.item("Orders (sales)", ColumnType.INT, sales.orders()));
        items.add(Sections.item("Voided/refunded orders", ColumnType.INT, sales.voidedOrRefunded()));
        items.add(Sections.item("Net orders", ColumnType.INT, sales.netOrders()));
        items.add(Sections.item("Gross sales", ColumnType.MONEY, money(sales.grossSales())));
        items.add(Sections.item("Discounts at sale", ColumnType.MONEY, money(sales.discountsAtSale())));
        items.add(Sections.item("Voids", ColumnType.MONEY, money(sales.voids())));
        items.add(Sections.item("Refunds", ColumnType.MONEY, money(sales.refunds())));
        items.add(Sections.item("Extras removed", ColumnType.MONEY, money(sales.extrasRemoved())));
        if (sales.legacyDiscountCount() > 0) {
            items.add(Sections.item("Discounts after payment (legacy)", ColumnType.MONEY, money(sales.legacyDiscounts())));
        }
        items.add(Sections.total("Net sales", ColumnType.MONEY, money(sales.netSales())));
        items.add(Sections.item("Average order value", ColumnType.MONEY, ReportValues.divide(sales.netSales(), sales.netOrders())));
        return Sections.keyValue("sales", "Sales", items);
    }

    private static List<Map<String, Object>> complimentarySections(ReportFacts.ComplimentaryFacts totals,
                                                                   List<EndOfDayFacts.ReasonCount> byReason) {
        var summary = Sections.keyValue("complimentary", "Complimentary meals (not part of sales)", List.of(
                Sections.item("Complimentary meals", ColumnType.INT, totals.orders()),
                Sections.item("Value at list price", ColumnType.MONEY, money(totals.value()))));
        var columns = List.of(
                ReportColumn.of("reason", "Reason", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("meals", "Meals", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("value", "Value", ColumnType.MONEY, ColumnTotal.SUM));
        var rows = new ArrayList<Map<String, Object>>();
        for (var reason : byReason) {
            var row = new LinkedHashMap<String, Object>();
            row.put("reason", humanize(reason.reasonCode()));
            row.put("meals", reason.orders());
            row.put("value", money(reason.value()));
            rows.add(row);
        }
        var table = Sections.table("complimentaryByReason", "Complimentary meals by reason", columns, rows,
                totalsRow("reason", Map.of("meals", totals.orders(), "value", money(totals.value()))),
                "reason", "meals", "value");
        return List.of(summary, table);
    }

    private static Map<String, Object> cashSection(List<ShiftFacts> shifts, Map<Long, ShiftCashFigures.Figures> figures,
                                                   BigDecimal netCashMovement) {
        var floats = BigDecimal.ZERO;
        var expected = BigDecimal.ZERO;
        BigDecimal counted = null;
        BigDecimal variance = null;
        for (var shift : shifts) {
            floats = floats.add(shift.openingFloat());
            expected = expected.add(figures.get(shift.shiftId()).expectedCash());
            if (!shift.open() && shift.closingCash() != null && shift.variance() != null) {
                counted = (counted == null ? BigDecimal.ZERO : counted).add(shift.closingCash());
                variance = (variance == null ? BigDecimal.ZERO : variance).add(shift.variance());
            }
        }
        return Sections.keyValue("cash", "Cash", List.of(
                Sections.item("Opening floats", ColumnType.MONEY, money(floats)),
                Sections.item("Net cash movement", ColumnType.MONEY, money(netCashMovement)),
                Sections.item("Expected cash", ColumnType.MONEY, money(expected)),
                Sections.item("Counted cash (closed shifts)", ColumnType.MONEY, counted == null ? null : money(counted)),
                Sections.total("Variance", ColumnType.VARIANCE, variance == null ? null : money(variance))));
    }

    private static Map<String, Object> shiftsTable(List<ShiftFacts> shifts, Map<Long, ShiftCashFigures.Figures> figures,
                                                   Map<Long, ShiftSignoffs.State> states, boolean inProcess) {
        var columns = List.of(
                ReportColumn.of("shiftId", "Shift", ColumnType.INT, ColumnTotal.LABEL),
                ReportColumn.of("cashier", "Cashier", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("openedAt", "Opened", ColumnType.DATETIME, ColumnTotal.NONE),
                ReportColumn.of("closedAt", "Closed", ColumnType.DATETIME, ColumnTotal.NONE),
                ReportColumn.of("openingFloat", "Float", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("cashSales", "Cash sales", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("cashRefunds", "Cash refunds", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("expectedCash", "Expected", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("countedCash", "Counted", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("variance", "Variance", ColumnType.VARIANCE, ColumnTotal.SUM),
                ReportColumn.of("signedOffBy", "Signed off", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("signedOffAt", "Signed off at", ColumnType.DATETIME, ColumnTotal.NONE));
        var rows = new ArrayList<Map<String, Object>>();
        var sums = new LinkedHashMap<String, Object>();
        for (var key : List.of("openingFloat", "cashSales", "cashRefunds", "expectedCash")) {
            sums.put(key, ReportValues.money(BigDecimal.ZERO));
        }
        for (var shift : shifts) {
            var cash = figures.get(shift.shiftId());
            var state = states.get(shift.shiftId());
            var signed = state != null && state.signedOff();
            var row = new LinkedHashMap<String, Object>();
            row.put("shiftId", shift.shiftId());
            row.put("cashier", shift.ownerName());
            row.put("openedAt", UtcTimestamps.toInstant(shift.openedAtUtc()));
            row.put("closedAt", shift.open() ? null : UtcTimestamps.toInstant(shift.closedAtUtc()));
            row.put("openingFloat", money(shift.openingFloat()));
            row.put("cashSales", money(cash.cashSales()));
            row.put("cashRefunds", money(cash.cashRefunds()));
            row.put("expectedCash", money(cash.expectedCash()));
            row.put("countedCash", shift.open() ? null : money(shift.closingCash()));
            row.put("variance", shift.open() ? null : money(shift.variance()));
            row.put("signedOffBy", signed ? state.byName() : inProcess ? "Not signed off" : NOT_REQUIRED);
            row.put("signedOffAt", signed ? state.at() : null);
            rows.add(row);
            for (var key : sums.keySet()) {
                sums.put(key, ((BigDecimal) sums.get(key)).add((BigDecimal) row.get(key)));
            }
            if (!shift.open() && shift.closingCash() != null && shift.variance() != null) {
                sums.merge("countedCash", money(shift.closingCash()), (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
                sums.merge("variance", money(shift.variance()), (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
            }
        }
        return Sections.table("shifts", "Shifts", columns, rows, totalsRow("shiftId", sums), "shiftId", "cashier", "variance");
    }

    private record Readings(Map<String, Object> section, ReportCheck check) {
    }

    /** Σ of each shift's takings by tender, against the day's tender figures. */
    private static Readings readingsVsX(List<ShiftFacts> shifts, Map<Long, EndOfDayFacts.ShiftTakings> takings,
                                        ReportFacts.CollectedFacts x) {
        var cashier = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (var shift : shifts) {
            var t = takings.getOrDefault(shift.shiftId(), EndOfDayFacts.ShiftTakings.NONE);
            cashier[0] = cashier[0].add(t.cash());
            cashier[1] = cashier[1].add(t.card());
            cashier[2] = cashier[2].add(t.account());
        }
        var labels = List.of("Cash", "Card", "Account");
        var xFigures = List.of(x.cash(), x.card(), x.account());
        var columns = List.of(
                ReportColumn.of("tender", "Tender", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("cashierReadings", "Cashier readings", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("xReading", "X reading", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("difference", "Difference", ColumnType.VARIANCE, ColumnTotal.SUM));
        var rows = new ArrayList<Map<String, Object>>();
        var differences = new ArrayList<String>();
        var cashierTotal = BigDecimal.ZERO;
        for (var i = 0; i < labels.size(); i++) {
            var difference = money(cashier[i].subtract(xFigures.get(i)));
            var row = new LinkedHashMap<String, Object>();
            row.put("tender", labels.get(i));
            row.put("cashierReadings", money(cashier[i]));
            row.put("xReading", money(xFigures.get(i)));
            row.put("difference", difference);
            rows.add(row);
            cashierTotal = cashierTotal.add(cashier[i]);
            if (difference.signum() != 0) {
                differences.add(labels.get(i) + " differs by " + difference.toPlainString());
            }
        }
        var totals = totalsRow("tender", Map.of("cashierReadings", money(cashierTotal), "xReading", money(x.collected()),
                "difference", money(cashierTotal.subtract(x.collected()))));
        var section = Sections.table("cashierReadingsVsX", "Cashier readings vs X reading", columns, rows, totals,
                "tender", "cashierReadings", "difference");

        var check = ReportCheck.compare("cashier-readings-agree-with-x",
                "The shifts' readings add up to the day's tender figures, per tender and in total",
                x.collected(), cashierTotal, differences.isEmpty() ? null : String.join("; ", differences) + ".");
        if (!differences.isEmpty() && check.status() == CheckStatus.PASS) {
            check = check.withStatus(CheckStatus.FAIL, String.join("; ", differences) + ".");
        }
        return new Readings(section, check);
    }

    private static Map<String, Object> signoffsTable(List<ShiftFacts> shifts, Map<Long, ShiftSignoffs.State> states) {
        var columns = List.of(
                ReportColumn.of("shiftId", "Shift", ColumnType.INT, ColumnTotal.NONE),
                ReportColumn.of("cashier", "Cashier", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("signedOffBy", "Signed off by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("signedOffAt", "Signed off at", ColumnType.DATETIME, ColumnTotal.NONE));
        var rows = new ArrayList<Map<String, Object>>();
        for (var shift : shifts) {
            var state = states.get(shift.shiftId());
            var row = new LinkedHashMap<String, Object>();
            row.put("shiftId", shift.shiftId());
            row.put("cashier", shift.ownerName());
            row.put("signedOffBy", state == null ? null : state.byName());
            row.put("signedOffAt", state == null ? null : state.at());
            rows.add(row);
        }
        return Sections.table("signoffs", "Sign-offs", columns, rows, null);
    }

    /**
     * The day's figures, taken again through the existing report definitions so drift between
     * code paths is caught: net sales (Sales Summary), total collected (Tender Summary), net cash
     * movement (Σ expected − float on Cash-up & Variance). A FAIL on any of those reports' own
     * checks for the day (e.g. a tampered order total) fails this too.
     */
    private ReportCheck agreesWithReports(LocalDate day, ReportFacts.SalesFacts sales, ReportFacts.CollectedFacts collected,
                                          BigDecimal netCashMovement) {
        var ranged = new HashMap<String, Object>();
        ranged.put("from", day);
        ranged.put("to", day);
        var byDay = new HashMap<>(ranged);
        byDay.put("groupBy", "DAY");
        var cashUpParams = new HashMap<>(ranged);
        cashUpParams.put("signoff", "ALL");

        var salesResult = salesSummary.run(ReportParams.of(byDay));
        var tenderResult = tenderSummary.run(ReportParams.of(byDay));
        var cashUpResult = cashUp.run(ReportParams.of(cashUpParams));

        var problems = new ArrayList<String>();
        compare(problems, "Net sales", sales.netSales(), (BigDecimal) salesResult.totals().get("netSales"), "Sales Summary");
        compare(problems, "Total collected", collected.collected(), (BigDecimal) tenderResult.totals().get("totalCollected"),
                "Tender Summary");
        var cashUps = cashUpResult.rows().stream()
                .map(row -> ((BigDecimal) row.get("expectedCash")).subtract((BigDecimal) row.get("openingFloat")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        compare(problems, "Net cash movement", netCashMovement, cashUps, "Cash-up & Variance");
        for (var result : List.of(salesResult, tenderResult, cashUpResult)) {
            for (var check : result.checks()) {
                if (check.status() == CheckStatus.FAIL) {
                    problems.add("check " + check.key() + " fails");
                }
            }
        }
        return ReportCheck.failures("x-agrees-with-reports",
                "Net sales, total collected and net cash movement agree with Sales Summary, Tender Summary and Cash-up",
                problems.size(), problems.isEmpty() ? null : String.join("; ", problems) + ".");
    }

    private static void compare(List<String> problems, String label, BigDecimal x, BigDecimal report, String reportName) {
        var difference = money(x).subtract(money(report));
        if (difference.signum() != 0) {
            problems.add(label + " " + money(x).toPlainString() + " vs " + money(report).toPlainString() + " on " + reportName);
        }
    }

    private ReportAction closeDayAction(LocalDate tradingDay) {
        var blockers = dayCloseRules.blockers(tradingDay);
        var all = zReadings.all();
        var next = ZReadings.Z.reference(all.isEmpty() ? 1 : all.get(0).zNumber() + 1);
        var day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).format(tradingDay);
        return new ReportAction("CLOSE_DAY", "Close day (run Z)", "POST", "/admin/days/" + tradingDay + "/close",
                "Close " + day + "? This produces " + next + " and you cannot open another shift for this day.",
                blockers.isEmpty(), blockers.stream().map(DayCloseRules.Blocker::message).toList());
    }

    // ---------------------------------------------------------------------------------------
    // Cashier reading: one shift (the drawer).

    public ReportResult cashierReading(long shiftId) {
        var shift = facts.shift(shiftId).orElseThrow(() -> ReportParamException.of("shiftId", "Select a shift"));
        var open = shift.open();
        var takings = endOfDayFacts.takings(shiftId);
        var cash = shiftCashFigures.figures(List.of(shift)).get(shiftId);
        var processed = endOfDayFacts.processedIn(shiftId);
        var others = endOfDayFacts.rungByOthers(shiftId);
        var complimentary = endOfDayFacts.complimentary(shiftId);
        var state = signoffs.latest(List.of(shiftId)).get(shiftId);
        var signed = state != null && state.signedOff();

        var sections = new ArrayList<Map<String, Object>>();
        var header = Sections.items();
        header.add(Sections.item("Business", ColumnType.TEXT, businessName));
        header.add(Sections.item("Report", ColumnType.TEXT, open ? CASHIER_TITLE + " — INTERIM" : CASHIER_TITLE));
        header.add(Sections.item("Cashier", ColumnType.TEXT, shift.ownerName()));
        header.add(Sections.item("Shift", ColumnType.INT, shiftId));
        header.add(Sections.item("Trading day", ColumnType.DATE, shift.tradingDay()));
        header.add(Sections.item("Opened", ColumnType.DATETIME, UtcTimestamps.toInstant(shift.openedAtUtc())));
        header.add(Sections.item("Closed", ColumnType.DATETIME, open ? null : UtcTimestamps.toInstant(shift.closedAtUtc())));
        header.add(Sections.item("Status", ColumnType.TEXT, open ? "Open (interim)" : "Closed"));
        sections.add(Sections.keyValue("header", null, header));

        sections.add(Sections.keyValue("takings", "Takings (orders sold in this shift)", List.of(
                Sections.item("Orders (sales)", ColumnType.INT, takings.orders()),
                Sections.item("Voided/refunded orders", ColumnType.INT, takings.voidedOrRefunded()),
                Sections.item("Gross sales", ColumnType.MONEY, money(takings.gross())),
                Sections.item("Discounts at sale", ColumnType.MONEY, money(takings.discountsAtSale())),
                Sections.item("Reductions after payment on these orders", ColumnType.MONEY, money(takings.reductionsAfterPayment())),
                Sections.total("Net sales", ColumnType.MONEY, money(takings.net())),
                Sections.item("Collected: cash", ColumnType.MONEY, money(takings.cash())),
                Sections.item("Collected: card", ColumnType.MONEY, money(takings.card())),
                Sections.item("Collected: account", ColumnType.MONEY, money(takings.account())),
                Sections.total("Total collected", ColumnType.MONEY, money(takings.collected())))));

        var cashUpItems = Sections.items();
        cashUpItems.add(Sections.item("Opening float", ColumnType.MONEY, money(shift.openingFloat())));
        cashUpItems.add(Sections.item("Cash sales", ColumnType.MONEY, money(cash.cashSales())));
        cashUpItems.add(Sections.item("Cash refunds processed in this shift", ColumnType.MONEY, money(cash.cashRefunds())));
        cashUpItems.add(Sections.total("Expected cash", ColumnType.MONEY, money(cash.expectedCash())));
        cashUpItems.add(Sections.item("Counted cash", ColumnType.MONEY, open ? null : money(shift.closingCash())));
        cashUpItems.add(Sections.total("Variance", ColumnType.VARIANCE, open ? null : money(shift.variance())));
        cashUpItems.add(Sections.item("Variance reason", ColumnType.TEXT, ShiftVarianceReasonCode.labelOf(shift.varianceReasonCode())));
        cashUpItems.add(Sections.item("Authorised by", ColumnType.TEXT, shift.varianceAuthorizedByName()));
        sections.add(Sections.keyValue("cashUp", "Cash-up", cashUpItems));

        sections.add(processedTable(shiftId, processed));

        sections.add(Sections.keyValue("rungByOthers", "Sales rung by someone else", List.of(
                Sections.item("Orders", ColumnType.INT, others.orders()),
                Sections.item("Value collected", ColumnType.MONEY, money(others.collected())),
                Sections.item("Rung by", ColumnType.TEXT, others.names().isEmpty() ? null : String.join(", ", others.names())))));
        sections.add(Sections.keyValue("complimentary", "Complimentary meals (not part of sales)", List.of(
                Sections.item("Complimentary meals", ColumnType.INT, complimentary.orders()),
                Sections.item("Value at list price", ColumnType.MONEY, money(complimentary.value())))));
        // A shift before the first trading day is outside sign-off: no warning, no action.
        var inProcess = closeDaySettings.covers(shift.tradingDay());
        sections.add(Sections.keyValue("signoff", "Sign-off", signed
                ? List.of(Sections.item("Signed off by", ColumnType.TEXT, state.byName()),
                        Sections.item("Signed off at", ColumnType.DATETIME, state.at()))
                : List.of(Sections.item("Status", ColumnType.TEXT, inProcess ? "Not yet signed off"
                        : NOT_REQUIRED + " (" + closeDaySettings.startsLabel() + ")"))));
        sections.add(Sections.signatures("signatures", "Cashier", "Manager"));

        var checks = new ArrayList<ReportCheck>();
        checks.add(agreesWithCashUp(shift, cash));
        if (inProcess && !signed) {
            checks.add(ReportCheck.count("not-signed-off", "The shift is signed off", 1, CheckStatus.WARN,
                    open ? "The shift is still open." : "The manager hasn't signed this shift off yet."));
        }
        var actions = new ArrayList<ReportAction>();
        if (inProcess && !open && !signed) {
            actions.add(new ReportAction("SIGN_OFF", "Sign off shift", "POST", "/admin/shifts/" + shiftId + "/signoff",
                    "Sign off shift #" + shiftId + " (" + shift.ownerName() + ")?", true, List.of(),
                    List.of(new ReportAction.Field("note", "Note", false))));
        }
        // A sign-off can be revoked (with a reason) until the day is closed.
        if (signed && zReadings.forDay(shift.tradingDay()).isEmpty()) {
            actions.add(new ReportAction("REVOKE_SIGN_OFF", "Revoke sign-off", "POST",
                    "/admin/shifts/" + shiftId + "/signoff/revoke",
                    "Revoke the sign-off on shift #" + shiftId + " (" + shift.ownerName()
                            + ")? The day can't be closed until it's signed off again.", true, List.of(),
                    List.of(new ReportAction.Field("note", "Reason", true))));
        }
        return ReportResult.statement(new ReportResult.Statement(CASHIER_TITLE, "Shift #" + shiftId, open), sections, checks,
                actions);
    }

    private static Map<String, Object> processedTable(long shiftId, List<EndOfDayFacts.Processed> processed) {
        var columns = List.of(
                ReportColumn.of("at", "Time", ColumnType.DATETIME, ColumnTotal.LABEL),
                ReportColumn.of("orderNumber", "Order #", ColumnType.INT, ColumnTotal.NONE),
                new ReportColumn("type", "Type", ColumnType.STATUS, ColumnTotal.NONE, ADJUSTMENT_TONES),
                ReportColumn.of("amount", "Amount", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("paidOutAs", "Paid out as", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("reason", "Reason", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("authorisedBy", "Authorised by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("soldInEarlierShift", "Note", ColumnType.TEXT, ColumnTotal.NONE));
        var rows = new ArrayList<Map<String, Object>>();
        var total = BigDecimal.ZERO;
        for (var item : processed) {
            var row = new LinkedHashMap<String, Object>();
            row.put("at", item.at());
            row.put("orderNumber", item.orderNumber());
            row.put("type", item.type());
            row.put("amount", money(item.amount()));
            row.put("paidOutAs", "ACCOUNT_BALANCE".equals(item.refundMethod()) ? "Account" : "Cash");
            row.put("reason", humanize(item.reasonCode()));
            row.put("authorisedBy", item.authorizedByName());
            row.put("soldInEarlierShift", item.soldInShiftId() != shiftId ? "Sold in earlier shift" : null);
            rows.add(row);
            total = total.add(item.amount());
        }
        return Sections.table("processed", "Processed in this shift", columns, rows, totalsRow("at", Map.of("amount", money(total))),
                "orderNumber", "type", "amount");
    }

    /** Expected, counted and variance equal this shift's row on Cash-up & Variance. */
    private ReportCheck agreesWithCashUp(ShiftFacts shift, ShiftCashFigures.Figures cash) {
        var params = new HashMap<String, Object>();
        params.put("from", shift.tradingDay());
        params.put("to", shift.tradingDay());
        params.put("signoff", "ALL");
        var row = cashUp.run(ReportParams.of(params)).rows().stream()
                .filter(r -> ((Long) r.get("shiftId")) == shift.shiftId())
                .findFirst().orElse(null);
        var problems = new ArrayList<String>();
        if (row == null) {
            problems.add("the shift is missing from Cash-up & Variance");
        } else {
            same(problems, "expected cash", money(cash.expectedCash()), (BigDecimal) row.get("expectedCash"));
            same(problems, "counted cash", shift.open() ? null : money(shift.closingCash()), (BigDecimal) row.get("countedCash"));
            same(problems, "variance", shift.open() ? null : money(shift.variance()), (BigDecimal) row.get("variance"));
        }
        return ReportCheck.failures("agrees-with-cash-up", "Expected, counted and variance agree with Cash-up & Variance",
                problems.size(), problems.isEmpty() ? null : "Differs on " + String.join(", ", problems) + ".");
    }

    private static void same(List<String> problems, String label, BigDecimal a, BigDecimal b) {
        if (a == null ? b != null : b == null || a.compareTo(b) != 0) {
            problems.add(label);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static boolean signedOff(Map<Long, ShiftSignoffs.State> states, long shiftId) {
        var state = states.get(shiftId);
        return state != null && state.signedOff();
    }

    private static ReportFacts.CollectedFacts sumCollected(List<ReportFacts.CollectedFacts> rows) {
        var cash = BigDecimal.ZERO;
        var card = BigDecimal.ZERO;
        var account = BigDecimal.ZERO;
        var collected = BigDecimal.ZERO;
        long split = 0;
        for (var row : rows) {
            cash = cash.add(row.cash());
            card = card.add(row.card());
            account = account.add(row.account());
            collected = collected.add(row.collected());
            split += row.splitOrders();
        }
        return new ReportFacts.CollectedFacts(null, cash, card, account, collected, split);
    }

    private static ReportFacts.PaidOutFacts sumPaidOut(List<ReportFacts.PaidOutFacts> rows) {
        var cash = BigDecimal.ZERO;
        var credits = BigDecimal.ZERO;
        for (var row : rows) {
            cash = cash.add(row.cashPaidOut());
            credits = credits.add(row.accountCredits());
        }
        return new ReportFacts.PaidOutFacts(null, cash, credits);
    }

    private static Map<String, Object> totalsRow(String labelKey, Map<String, Object> values) {
        var totals = new LinkedHashMap<String, Object>();
        totals.put(labelKey, "Total");
        totals.putAll(values);
        return totals;
    }

    private static BigDecimal money(BigDecimal value) {
        return ReportValues.money(value);
    }

    /** CASH_COUNTING_ERROR → "Cash counting error". */
    static String humanize(String code) {
        if (code == null) {
            return null;
        }
        var text = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
