package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.CheckStatus;
import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.EndOfDayFacts;
import com.beaconculinary.api.reports.ParamType;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportEnvelope;
import com.beaconculinary.api.reports.ReportParamException;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Reports R1 §2.2. Collected is by day of sale; paid out is by day processed. */
@Component
@AllArgsConstructor
public class TenderSummaryReport implements ReportDefinition {
    static final String CHECK = "CHECK";
    private static final int MAX_CHECK_DAYS = 31;
    private static final Map<String, String> ORDER_STATUS_TONES = Map.of(
            "PENDING", "neutral", "IN_PROGRESS", "neutral", "DONE", "neutral", "COLLECTED", "positive",
            "VOIDED", "negative", "REFUNDED", "warning");
    private static final List<String> MONEY_KEYS = List.of(
            "cash", "card", "account", "totalCollected", "cashPaidOut", "accountCredits", "netCashMovement");

    private final ReportFacts facts;
    private final ShiftCashFigures shiftCashFigures;
    private final EndOfDayFacts endOfDayFacts;

    @Override
    public String key() {
        return "tender-summary";
    }

    @Override
    public String title() {
        return "Tender Summary";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.PAYMENTS_ACCOUNTS;
    }

    @Override
    public String description() {
        return "Money collected by payment method, money paid out, and net cash movement.";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                new ReportParamSpec("groupBy", "Group by", ParamType.ENUM, GroupBy.DAY.name(),
                        List.of(GroupBy.DAY.name(), GroupBy.WEEK.name(), GroupBy.MONTH.name(), CHECK)),
                new ReportParamSpec("checkNumber", "Check #", ParamType.INT, null, null).when("groupBy", CHECK));
    }

    /** 2: discounts moved to checkout — the collected check now compares with gross sales less
     * discounts at sale (key collected-equals-gross-less-sale-discounts).
     * 3: groupBy CHECK, one paged row per sales order with payments, with a Reprint action. */
    @Override
    public int definitionVersion() {
        return 3;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Collected is what was actually taken for each order, after any discount at sale, by payment method, "
                        + "on the trading day the order was sold.",
                "Paid out is money returned after payment by a void or refund (or an extras removal), on the trading day "
                        + "it was processed. A refund on a card order is paid out as cash. Older discounts given after payment "
                        + "also count here.",
                "Discounts at sale are not paid out; they simply reduce what was collected.",
                "Net cash movement is cash collected minus cash paid out: what the cash drawers should have gained on top "
                        + "of their opening floats.",
                "Split orders were paid with more than one payment method.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        if (CHECK.equals(params.string("groupBy"))) {
            return runChecks(params);
        }
        var range = params.range();
        var groupBy = params.groupBy();

        var columns = List.of(
                ReportColumn.of("period", groupBy.columnLabel(), groupBy.columnType(), ColumnTotal.LABEL),
                ReportColumn.of("cash", "Cash", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("card", "Card", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("account", "Account", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("totalCollected", "Total collected", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("cashPaidOut", "Cash paid out", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("accountCredits", "Account credits", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("netCashMovement", "Net cash movement", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("splitOrders", "Split orders", ColumnType.INT, ColumnTotal.SUM));

        // Collected and paid out bucket on different days (sale vs processed), so a period can
        // have one without the other. Bucket keys sort chronologically as text.
        var byBucket = new TreeMap<String, Map<String, Object>>();
        for (var c : facts.collected(range, groupBy)) {
            var row = row(byBucket, c.bucket(), groupBy);
            row.put("cash", ReportValues.money(c.cash()));
            row.put("card", ReportValues.money(c.card()));
            row.put("account", ReportValues.money(c.account()));
            row.put("totalCollected", ReportValues.money(c.collected()));
            row.put("splitOrders", c.splitOrders());
        }
        for (var p : facts.paidOut(range, groupBy)) {
            var row = row(byBucket, p.bucket(), groupBy);
            row.put("cashPaidOut", ReportValues.money(p.cashPaidOut()));
            row.put("accountCredits", ReportValues.money(p.accountCredits()));
        }

        var totals = new LinkedHashMap<String, Object>();
        totals.put("period", "Total");
        MONEY_KEYS.forEach(key -> totals.put(key, ReportValues.money(BigDecimal.ZERO)));
        totals.put("splitOrders", 0L);

        var rows = new ArrayList<Map<String, Object>>();
        for (var row : byBucket.values()) {
            row.put("netCashMovement", ((BigDecimal) row.get("cash")).subtract((BigDecimal) row.get("cashPaidOut")));
            for (var key : MONEY_KEYS) {
                totals.put(key, ((BigDecimal) totals.get(key)).add((BigDecimal) row.get(key)));
            }
            totals.put("splitOrders", (Long) totals.get("splitOrders") + (Long) row.get("splitOrders"));
            rows.add(row);
        }

        var sales = facts.salesTotals(range);
        var checks = List.of(
                ReportCheck.compare("collected-equals-gross-less-sale-discounts", "Collected equals gross sales less discounts at sale",
                        sales.grossSales().subtract(sales.discountsAtSale()), (BigDecimal) totals.get("totalCollected"), null),
                shiftCashFigures.check("cash-matches-cashups", "Net cash movement equals the cash-ups (expected cash − opening float)",
                        shiftCashFigures.reconcile(range), false));

        return new ReportResult(columns, rows, totals, checks);
    }

    /**
     * groupBy CHECK (R3): one row per sales order with payments (complimentary orders have none),
     * by day of sale and daily order number, at most 31 days, paged. Totals cover every
     * matching order, and must equal the DAY grouping's collected totals.
     */
    private ReportResult runChecks(ReportParams params) {
        var range = params.range();
        if (ChronoUnit.DAYS.between(range.from(), range.to()) + 1 > MAX_CHECK_DAYS) {
            throw ReportParamException.of("to", "The check list covers at most " + MAX_CHECK_DAYS + " days.");
        }
        var checkNumber = params.integer("checkNumber");
        var paging = params.paging();
        var totals = endOfDayFacts.checkTotals(range, checkNumber);

        var columns = List.of(
                ReportColumn.of("date", "Date", ColumnType.DATE, ColumnTotal.LABEL),
                ReportColumn.of("checkNumber", "Check #", ColumnType.INT, ColumnTotal.NONE),
                ReportColumn.of("time", "Time", ColumnType.DATETIME, ColumnTotal.NONE),
                ReportColumn.of("rungBy", "Rung by", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("cash", "Cash", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("card", "Card", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("account", "Account", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("total", "Total", ColumnType.MONEY, ColumnTotal.SUM),
                new ReportColumn("status", "Order status", ColumnType.STATUS, ColumnTotal.NONE, ORDER_STATUS_TONES));

        var rows = new ArrayList<Map<String, Object>>();
        for (var check : endOfDayFacts.checks(range, checkNumber, paging)) {
            var row = new LinkedHashMap<String, Object>();
            row.put("orderId", check.orderId());
            row.put("date", check.day());
            row.put("checkNumber", check.orderNumber());
            row.put("time", check.createdAt());
            row.put("rungBy", check.rungBy());
            row.put("cash", ReportValues.money(check.cash()));
            row.put("card", ReportValues.money(check.card()));
            row.put("account", ReportValues.money(check.account()));
            row.put("total", ReportValues.money(check.collected()));
            row.put("status", check.status());
            row.put(RowAction.ROW_KEY, List.of(RowAction.post("REPRINT", "Reprint", "/admin/orders/" + check.orderId()
                    + "/reprint-receipt", null, RowAction.OnSuccess.PRINT_RECEIPT)));
            rows.add(row);
        }

        var totalsRow = new LinkedHashMap<String, Object>();
        totalsRow.put("date", "Total");
        totalsRow.put("cash", ReportValues.money(totals.cash()));
        totalsRow.put("card", ReportValues.money(totals.card()));
        totalsRow.put("account", ReportValues.money(totals.account()));
        totalsRow.put("total", ReportValues.money(totals.collected()));

        // Against the DAY grouping over the same range (all orders, so only without a check filter).
        var checks = new ArrayList<ReportCheck>();
        if (checkNumber == null) {
            var daily = facts.collected(range, GroupBy.DAY);
            var dayCash = daily.stream().map(ReportFacts.CollectedFacts::cash).reduce(BigDecimal.ZERO, BigDecimal::add);
            var dayCard = daily.stream().map(ReportFacts.CollectedFacts::card).reduce(BigDecimal.ZERO, BigDecimal::add);
            var dayAccount = daily.stream().map(ReportFacts.CollectedFacts::account).reduce(BigDecimal.ZERO, BigDecimal::add);
            var dayTotal = daily.stream().map(ReportFacts.CollectedFacts::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
            var differences = new ArrayList<String>();
            differs(differences, "cash", totals.cash(), dayCash);
            differs(differences, "card", totals.card(), dayCard);
            differs(differences, "account", totals.account(), dayAccount);
            var check = ReportCheck.compare("rows-agree-with-daily-totals",
                    "The checks add up to the daily totals for the range (cash, card, account and total)", dayTotal,
                    totals.collected(), differences.isEmpty() ? null : "Differs on " + String.join(", ", differences) + ".");
            if (!differences.isEmpty() && check.status() == CheckStatus.PASS) {
                check = check.withStatus(CheckStatus.FAIL, check.detail());
            }
            checks.add(check);
        }

        var page = paging.all() ? null : new ReportEnvelope.Page(paging.number(), paging.size(), totals.rows(), true);
        return new ReportResult(columns, rows, totalsRow, checks, page);
    }

    private static void differs(List<String> differences, String label, BigDecimal a, BigDecimal b) {
        if (ReportValues.money(a).compareTo(ReportValues.money(b)) != 0) {
            differences.add(label);
        }
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> byBucket, String bucket, GroupBy groupBy) {
        return byBucket.computeIfAbsent(bucket, key -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("period", groupBy.label(key));
            MONEY_KEYS.forEach(moneyKey -> row.put(moneyKey, ReportValues.money(BigDecimal.ZERO)));
            row.put("splitOrders", 0L);
            return row;
        });
    }
}
