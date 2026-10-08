package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.reports.ShiftCashFigures;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
    private static final List<String> MONEY_KEYS = List.of(
            "cash", "card", "account", "totalCollected", "cashPaidOut", "accountCredits", "netCashMovement");

    private final ReportFacts facts;
    private final ShiftCashFigures shiftCashFigures;

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
                ReportParamSpec.groupBy(List.of(GroupBy.DAY, GroupBy.WEEK, GroupBy.MONTH), GroupBy.DAY));
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Collected is what customers paid, by payment method, on the trading day the order was sold.",
                "Cash paid out and account credits are voids, refunds, discounts and extras removed, on the trading day "
                        + "they were processed. A refund on a card order is paid out in cash, so it appears under Cash paid out.",
                "Net cash movement is cash collected minus cash paid out: what the cash drawers should have gained on top "
                        + "of their opening floats.",
                "Split orders were paid with more than one payment method.");
    }

    @Override
    public ReportResult run(ReportParams params) {
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

        var grossSales = facts.salesTotals(range).grossSales();
        var checks = List.of(
                ReportCheck.compare("collected-equals-gross", "Total collected equals gross sales (Sales Summary)",
                        grossSales, (BigDecimal) totals.get("totalCollected"), null),
                shiftCashFigures.check("cash-matches-cashups", "Net cash movement equals the cash-ups (expected cash − opening float)",
                        shiftCashFigures.reconcile(range), false));

        return new ReportResult(columns, rows, totals, checks);
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
