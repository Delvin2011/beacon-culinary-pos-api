package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.ItemSalesFacts;
import com.beaconculinary.api.reports.ItemSalesFacts.ItemFacts;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportEnvelope;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reports R2 §4. Portions and gross as rung per item and meal period, including items that were
 * offered but sold nothing. Revenue is before discounts: discounts and adjustments belong to
 * whole orders and are never spread across items.
 */
@Component
@AllArgsConstructor
public class MealExtrasSalesReport implements ReportDefinition {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ItemSalesFacts itemFacts;
    private final ReportFacts facts;

    @Override
    public String key() {
        return "meal-extras-sales";
    }

    @Override
    public String title() {
        return "Meal & Extras Sales";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.SALES;
    }

    @Override
    public String description() {
        return "Portions sold of every meal and extra, including items offered but not sold.";
    }

    @Override
    public Set<Role> allowedRoles() {
        // Item sales support production planning; no cash or account figures are shown.
        return Set.of(Role.ADMIN, Role.HEAD_CHEF);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                ReportParamSpec.choice("groupBy", "Group by", "ITEM", "MEAL_PERIOD"),
                ReportParamSpec.choice("type", "Type", "ALL", "MEAL", "EXTRA"),
                ReportParamSpec.id("mealPeriodId", "Meal period", itemFacts.mealPeriodOptions()),
                ReportParamSpec.choice("sortBy", "Sort by", "NET_PORTIONS", "GROSS_SALES"),
                ReportParamSpec.choice("direction", "Direction", "DESC", "ASC"),
                ReportParamSpec.limit("ALL", "10", "20", "50"));
    }

    /** 2: complimentary portions and their list value in their own columns, outside rung,
     * net and gross. */
    @Override
    public int definitionVersion() {
        return 2;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Portions rung are everything the cashiers sold, before cancellations.",
                "Voided portions are from orders voided whole. Refunded or removed portions are from orders refunded "
                        + "whole, or are extras taken off an order.",
                "Net portions are what was actually sold.",
                "Gross as rung is the price of every rung portion before discounts, voids and refunds. Discounts and "
                        + "adjustments apply to whole orders and are not spread across items; see Sales Summary for net sales.",
                "Items offered but not sold show zero. Days offered is the number of days the item was on the daily plan.",
                "Days are trading days: an order counts on the day its shift opened.",
                "Complimentary portions were given away with manager approval and no payment. They are not part of "
                        + "portions rung, net portions or gross; their value is at list price. Voided complimentary orders "
                        + "are not counted.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        var byPeriod = params.string("groupBy").equals("MEAL_PERIOD");
        var type = params.string("type");
        var items = itemFacts.items(range, type.equals("ALL") ? null : type, params.id("mealPeriodId"));

        var totalGross = items.stream().map(ItemFacts::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        var rows = byPeriod ? periodRows(items) : itemRows(items);

        // Rank and share are computed over every matching row, before the limit.
        var byGross = params.string("sortBy").equals("GROSS_SALES");
        Comparator<Map<String, Object>> order = byGross
                ? Comparator.comparing(row -> (BigDecimal) row.get("grossAsRung"))
                : Comparator.comparing(row -> (Long) row.get("netPortions"));
        if (params.string("direction").equals("DESC")) {
            order = order.reversed();
        }
        order = order.thenComparing(byPeriod ? tieBreak("mealPeriod", "periodStart") : itemTieBreak());
        rows.sort(order);

        var rank = 1;
        for (var row : rows) {
            if (!byPeriod) {
                row.put("rank", rank++);
            }
            row.put("shareOfGross", share((BigDecimal) row.get("grossAsRung"), totalGross));
        }

        var totals = totals(rows, totalGross, byPeriod);
        var limit = params.limit();
        ReportEnvelope.Page page = null;
        var shown = new ArrayList<Map<String, Object>>(rows);
        if (limit != null) {
            page = new ReportEnvelope.Page(0, limit, rows.size());
            shown = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        }
        shown.forEach(row -> row.remove("periodStart"));

        var check = ReportCheck.compare("gross-agrees-with-sales-summary",
                "Gross as rung for all items equals Sales Summary gross sales",
                facts.salesTotals(range).grossSales(), itemFacts.grossAsRung(range), null);

        return new ReportResult(byPeriod ? periodColumns() : itemColumns(), shown, totals, List.of(check), page);
    }

    private static List<Map<String, Object>> itemRows(List<ItemFacts> items) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            var row = new LinkedHashMap<String, Object>();
            row.put("rank", null);
            row.put("item", item.itemName());
            row.put("type", item.itemType().equals("MEAL") ? "Meal" : "Extra");
            row.put("plu", item.pluCode());
            row.put("mealPeriod", item.mealPeriodName());
            row.put("daysOffered", item.daysOffered());
            row.put("portionsRung", item.rung());
            row.put("voided", item.voided());
            row.put("refundedOrRemoved", item.refundedOrRemoved());
            row.put("netPortions", item.net());
            row.put("avgNetPerDayOffered", item.daysOffered() == 0 ? null
                    : BigDecimal.valueOf(item.net()).divide(BigDecimal.valueOf(item.daysOffered()), 2, RoundingMode.HALF_UP));
            row.put("grossAsRung", ReportValues.money(item.gross()));
            row.put("shareOfGross", null);
            row.put("complimentaryPortions", item.complimentaryPortions());
            row.put("complimentaryValue", ReportValues.money(item.complimentaryValue()));
            row.put("periodStart", item.mealPeriodStart());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> periodRows(List<ItemFacts> items) {
        var byPeriod = new LinkedHashMap<Long, Map<String, Object>>();
        for (var item : items) {
            var row = byPeriod.computeIfAbsent(item.mealPeriodId(), id -> {
                var r = new LinkedHashMap<String, Object>();
                r.put("mealPeriod", item.mealPeriodName());
                r.put("itemsOffered", 0L);
                r.put("portionsRung", 0L);
                r.put("voided", 0L);
                r.put("refundedOrRemoved", 0L);
                r.put("netPortions", 0L);
                r.put("grossAsRung", ReportValues.money(BigDecimal.ZERO));
                r.put("shareOfGross", null);
                r.put("complimentaryPortions", 0L);
                r.put("complimentaryValue", ReportValues.money(BigDecimal.ZERO));
                r.put("periodStart", item.mealPeriodStart());
                return r;
            });
            if (item.daysOffered() > 0) {
                row.merge("itemsOffered", 1L, (a, b) -> (Long) a + (Long) b);
            }
            row.merge("portionsRung", item.rung(), (a, b) -> (Long) a + (Long) b);
            row.merge("voided", item.voided(), (a, b) -> (Long) a + (Long) b);
            row.merge("refundedOrRemoved", item.refundedOrRemoved(), (a, b) -> (Long) a + (Long) b);
            row.merge("netPortions", item.net(), (a, b) -> (Long) a + (Long) b);
            row.merge("grossAsRung", ReportValues.money(item.gross()), (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
            row.merge("complimentaryPortions", item.complimentaryPortions(), (a, b) -> (Long) a + (Long) b);
            row.merge("complimentaryValue", ReportValues.money(item.complimentaryValue()),
                    (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
        }
        return new ArrayList<>(byPeriod.values());
    }

    /** Equal values: item name A–Z, then the meal period in order of the day. */
    private static Comparator<Map<String, Object>> itemTieBreak() {
        return Comparator.<Map<String, Object>, String>comparing(row -> (String) row.get("item"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(tieBreak("mealPeriod", "periodStart"));
    }

    /** Meal periods in order of the day (all-day last), then by name. */
    private static Comparator<Map<String, Object>> tieBreak(String nameKey, String startKey) {
        Function<Map<String, Object>, LocalTime> start = row -> (LocalTime) row.get(startKey);
        return Comparator.comparing(start, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> (String) row.get(nameKey), String.CASE_INSENSITIVE_ORDER);
    }

    private static BigDecimal share(BigDecimal gross, BigDecimal totalGross) {
        if (totalGross.signum() == 0) {
            return ReportValues.money(BigDecimal.ZERO);
        }
        return gross.multiply(HUNDRED).divide(totalGross, 2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> totals(List<Map<String, Object>> rows, BigDecimal totalGross, boolean byPeriod) {
        var totals = new LinkedHashMap<String, Object>();
        totals.put(byPeriod ? "mealPeriod" : "rank", "Total");
        var countKeys = byPeriod
                ? List.of("itemsOffered", "portionsRung", "voided", "refundedOrRemoved", "netPortions")
                : List.of("portionsRung", "voided", "refundedOrRemoved", "netPortions");
        for (var key : countKeys) {
            totals.put(key, rows.stream().mapToLong(row -> (Long) row.get(key)).sum());
        }
        totals.put("grossAsRung", ReportValues.money(totalGross));
        totals.put("shareOfGross", ReportValues.money(totalGross.signum() == 0 ? BigDecimal.ZERO : HUNDRED));
        totals.put("complimentaryPortions", rows.stream().mapToLong(row -> (Long) row.get("complimentaryPortions")).sum());
        totals.put("complimentaryValue", rows.stream().map(row -> (BigDecimal) row.get("complimentaryValue"))
                .reduce(ReportValues.money(BigDecimal.ZERO), BigDecimal::add));
        return totals;
    }

    private static List<ReportColumn> itemColumns() {
        return List.of(
                ReportColumn.of("rank", "Rank", ColumnType.INT, ColumnTotal.LABEL),
                ReportColumn.of("item", "Item", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("type", "Type", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("plu", "PLU", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("mealPeriod", "Meal period", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("daysOffered", "Days offered", ColumnType.INT, ColumnTotal.NONE),
                ReportColumn.of("portionsRung", "Portions rung", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("voided", "Voided", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("refundedOrRemoved", "Refunded/removed", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("netPortions", "Net portions", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("avgNetPerDayOffered", "Avg net per day offered", ColumnType.DECIMAL, ColumnTotal.NONE),
                ReportColumn.of("grossAsRung", "Gross as rung", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("shareOfGross", "Share of gross", ColumnType.PERCENT, ColumnTotal.DERIVED),
                ReportColumn.of("complimentaryPortions", "Complimentary portions", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("complimentaryValue", "Complimentary value (list price)", ColumnType.MONEY, ColumnTotal.SUM));
    }

    private static List<ReportColumn> periodColumns() {
        return List.of(
                ReportColumn.of("mealPeriod", "Meal period", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("itemsOffered", "Items offered", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("portionsRung", "Portions rung", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("voided", "Voided", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("refundedOrRemoved", "Refunded/removed", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("netPortions", "Net portions", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("grossAsRung", "Gross as rung", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("shareOfGross", "Share of gross", ColumnType.PERCENT, ColumnTotal.DERIVED),
                ReportColumn.of("complimentaryPortions", "Complimentary portions", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("complimentaryValue", "Complimentary value (list price)", ColumnType.MONEY, ColumnTotal.SUM));
    }
}
