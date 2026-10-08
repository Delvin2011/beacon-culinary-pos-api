package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportFacts.SalesFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reports R1 §2.1. */
@Component
@AllArgsConstructor
public class SalesSummaryReport implements ReportDefinition {
    private final ReportFacts facts;

    @Override
    public String key() {
        return "sales-summary";
    }

    @Override
    public String title() {
        return "Sales Summary";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.SALES;
    }

    @Override
    public String description() {
        return "Orders and sales by day, week, month or hour of day.";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                ReportParamSpec.groupBy(List.of(GroupBy.DAY, GroupBy.WEEK, GroupBy.MONTH, GroupBy.HOUR), GroupBy.DAY));
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Gross sales are what was rung up, at each order's original total.",
                "Reductions are voids, refunds, discounts and extras removed. They count against the day (or hour) "
                        + "the order was sold, even when they were processed later.",
                "Net sales are gross sales minus reductions. Average order value is net sales divided by net orders "
                        + "(orders that were not voided or refunded).",
                "Days are trading days: an order counts on the day its shift opened, so an overnight shift stays on one day. "
                        + "Hour rows use the time each order was rung up, added together across the whole range.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        var groupBy = params.groupBy();

        var columns = List.of(
                ReportColumn.of("period", groupBy.columnLabel(), groupBy.columnType(), ColumnTotal.LABEL),
                ReportColumn.of("orders", "Orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("voidedOrRefundedOrders", "Voided/refunded orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("netOrders", "Net orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("grossSales", "Gross sales", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("voids", "Voids", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("refunds", "Refunds", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("discounts", "Discounts", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("extrasRemoved", "Extras removed", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("netSales", "Net sales", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("averageOrderValue", "Average order value", ColumnType.MONEY, ColumnTotal.DERIVED));

        var rows = facts.sales(range, groupBy).stream()
                .map(row -> toRow(groupBy.label(row.bucket()), row))
                .toList();
        // Totals come from one query over the whole range, so the average is net / net orders
        // for the range, never an average of the rows' averages.
        var totalsFacts = facts.salesTotals(range);
        var totals = toRow("Total", totalsFacts);

        var check = ReportCheck.compare("net-agrees-with-orders", "Gross − reductions equals the sum of order totals",
                totalsFacts.orderTotals(), totalsFacts.grossSales().subtract(totalsFacts.reductions()), null);

        return new ReportResult(columns, rows, totals, List.of(check));
    }

    private static Map<String, Object> toRow(Object period, SalesFacts facts) {
        var row = new LinkedHashMap<String, Object>();
        row.put("period", period);
        row.put("orders", facts.orders());
        row.put("voidedOrRefundedOrders", facts.voidedOrRefunded());
        row.put("netOrders", facts.netOrders());
        row.put("grossSales", ReportValues.money(facts.grossSales()));
        row.put("voids", ReportValues.money(facts.voids()));
        row.put("refunds", ReportValues.money(facts.refunds()));
        row.put("discounts", ReportValues.money(facts.discounts()));
        row.put("extrasRemoved", ReportValues.money(facts.extrasRemoved()));
        row.put("netSales", ReportValues.money(facts.netSales()));
        row.put("averageOrderValue", ReportValues.divide(facts.netSales(), facts.netOrders()));
        return row;
    }
}
