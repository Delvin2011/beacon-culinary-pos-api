package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportFacts.ComplimentaryFacts;
import com.beaconculinary.api.reports.ReportFacts.SalesFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        // The Head Chef reviews food sales to support production planning. Tender Summary and
        // Cash-up show cash and stay ADMIN-only.
        return Set.of(Role.ADMIN, Role.HEAD_CHEF);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                ReportParamSpec.groupBy(List.of(GroupBy.DAY, GroupBy.WEEK, GroupBy.MONTH, GroupBy.HOUR), GroupBy.DAY));
    }

    /** 2: discounts moved to checkout — gross is the pre-discount total, "Discounts at sale"
     * replaces the adjustment-based Discounts column, plus a conditional legacy column.
     * 3: sales figures count sale orders only; complimentary orders and their list value get
     * two columns at the end, outside the net-sales identity. */
    @Override
    public int definitionVersion() {
        return 3;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Gross sales are what the orders would have cost with no discount.",
                "Discounts at sale are manager-approved discounts given before the customer paid; no cash was handed back for them.",
                "Voids, refunds and extras removed are adjustments made after payment. They count against the day (or hour) "
                        + "the order was sold, even when they were processed later.",
                "Net sales are gross sales less discounts at sale and less those adjustments. Average order value is net "
                        + "sales divided by net orders (orders that were not voided or refunded).",
                "Discounts after payment (legacy) appears only when the range includes orders discounted after payment, "
                        + "a feature that has since been retired.",
                "Days are trading days: an order counts on the day its shift opened, so an overnight shift stays on one day. "
                        + "Hour rows use the time each order was rung up, added together across the whole range.",
                COMPLIMENTARY_NOTE);
    }

    /** Shared with Cash-up & Variance. */
    public static final String COMPLIMENTARY_NOTE = "Complimentary meals are given away with manager approval and no "
            + "payment. They are shown separately and are not part of sales or net sales. Value is at list price.";

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        var groupBy = params.groupBy();

        // Totals come from one query over the whole range, so the average is net / net orders
        // for the range, never an average of the rows' averages. They also decide whether the
        // legacy column appears: only when an old after-payment discount is in the range, so the
        // columns (and an export's header) can differ between date ranges.
        var totalsFacts = facts.salesTotals(range);
        var showLegacy = totalsFacts.legacyDiscountCount() > 0;

        var columns = new ArrayList<ReportColumn>(List.of(
                ReportColumn.of("period", groupBy.columnLabel(), groupBy.columnType(), ColumnTotal.LABEL),
                ReportColumn.of("orders", "Orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("voidedOrRefundedOrders", "Voided/refunded orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("netOrders", "Net orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("grossSales", "Gross sales", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("discountsAtSale", "Discounts at sale", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("voids", "Voids", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("refunds", "Refunds", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("extrasRemoved", "Extras removed", ColumnType.MONEY, ColumnTotal.SUM)));
        if (showLegacy) {
            columns.add(ReportColumn.of("legacyDiscountsAfterPayment", "Discounts after payment (legacy)", ColumnType.MONEY, ColumnTotal.SUM));
        }
        columns.add(ReportColumn.of("netSales", "Net sales", ColumnType.MONEY, ColumnTotal.SUM));
        columns.add(ReportColumn.of("averageOrderValue", "Average order value", ColumnType.MONEY, ColumnTotal.DERIVED));
        columns.add(ReportColumn.of("complimentaryOrders", "Complimentary orders", ColumnType.INT, ColumnTotal.SUM));
        columns.add(ReportColumn.of("complimentaryValue", "Complimentary value (at list price)", ColumnType.MONEY, ColumnTotal.SUM));

        // A period can have complimentary orders and no sales, so rows come from both. Bucket
        // keys sort chronologically as text.
        var sales = new TreeMap<String, SalesFacts>();
        facts.sales(range, groupBy).forEach(row -> sales.put(row.bucket(), row));
        var complimentary = new TreeMap<String, ComplimentaryFacts>();
        facts.complimentary(range, groupBy).forEach(row -> complimentary.put(row.bucket(), row));
        var buckets = new TreeSet<>(sales.keySet());
        buckets.addAll(complimentary.keySet());

        var rows = buckets.stream()
                .map(bucket -> toRow(groupBy.label(bucket), sales.getOrDefault(bucket, SalesFacts.none(bucket)),
                        complimentary.getOrDefault(bucket, ComplimentaryFacts.none(bucket)), showLegacy))
                .toList();
        var totals = toRow("Total", totalsFacts, facts.complimentaryTotals(range), showLegacy);

        var actualNet = totalsFacts.grossSales().subtract(totalsFacts.discountsAtSale()).subtract(totalsFacts.reductionsAfterPayment());
        var check = ReportCheck.compare("net-agrees-with-orders",
                "Gross − discounts at sale − reductions after payment equals the sum of order totals",
                totalsFacts.orderTotals(), actualNet, null);

        return new ReportResult(columns, rows, totals, List.of(check));
    }

    private static Map<String, Object> toRow(Object period, SalesFacts facts, ComplimentaryFacts complimentary, boolean showLegacy) {
        var row = new LinkedHashMap<String, Object>();
        row.put("period", period);
        row.put("orders", facts.orders());
        row.put("voidedOrRefundedOrders", facts.voidedOrRefunded());
        row.put("netOrders", facts.netOrders());
        row.put("grossSales", ReportValues.money(facts.grossSales()));
        row.put("discountsAtSale", ReportValues.money(facts.discountsAtSale()));
        row.put("voids", ReportValues.money(facts.voids()));
        row.put("refunds", ReportValues.money(facts.refunds()));
        row.put("extrasRemoved", ReportValues.money(facts.extrasRemoved()));
        if (showLegacy) {
            row.put("legacyDiscountsAfterPayment", ReportValues.money(facts.legacyDiscounts()));
        }
        row.put("netSales", ReportValues.money(facts.netSales()));
        row.put("averageOrderValue", ReportValues.divide(facts.netSales(), facts.netOrders()));
        row.put("complimentaryOrders", complimentary.orders());
        row.put("complimentaryValue", ReportValues.money(complimentary.value()));
        return row;
    }
}
