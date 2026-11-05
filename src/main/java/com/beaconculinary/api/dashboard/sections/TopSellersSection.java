package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFacts.ItemSales;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardParamException;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What sells: the top 5 items by sold portions (net units: rung − voided − refunded/removed) or
 * by sold value (net units × order-time price, before discounts), from v_order_line_facts as in
 * Meal & Extras Sales and its "Sold value" column. An item sold in more than one meal period is
 * one row here (the report's rows for it, added up). Ties break by name A–Z. Items with no net
 * units are not listed. soldValue is left out entirely without the sales permission, and so is
 * sorting by it.
 */
@Component
@AllArgsConstructor
public class TopSellersSection implements SectionHandler {
    private static final int TOP = 5;

    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;

    @Override
    public DashboardSection section() {
        return DashboardSection.TOP_SELLERS;
    }

    @Override
    public Set<String> extraParams() {
        return Set.of("sortBy", "type");
    }

    @Override
    public Result compute(Request request) {
        var sortBy = choice(request.param("sortBy"), "sortBy", "PORTIONS", List.of("PORTIONS", "VALUE"));
        var type = choice(request.param("type"), "type", "ALL", List.of("ALL", "MEAL", "EXTRA"));
        var values = request.caller().seesValues();
        if (sortBy.equals("VALUE") && !values) {
            throw new AccessDeniedException("Sorting by sold value needs the sales permission.");
        }

        var range = calendar.span(request.range());
        Comparator<ItemSales> order = sortBy.equals("VALUE")
                ? Comparator.comparing(ItemSales::soldValue).reversed()
                : Comparator.comparingLong(ItemSales::netUnits).reversed();
        order = order.thenComparing(ItemSales::name, String.CASE_INSENSITIVE_ORDER);
        var top = dashboardFacts.itemSales(range, type.equals("ALL") ? null : type).stream()
                .filter(item -> item.netUnits() > 0)
                .sorted(order)
                .limit(TOP)
                .toList();

        var items = new ArrayList<Map<String, Object>>();
        var rank = 1;
        for (var item : top) {
            var row = new LinkedHashMap<String, Object>();
            row.put("rank", rank++);
            row.put("name", item.name());
            row.put("tag", tag(item));
            row.put("portions", item.netUnits());
            if (values) {
                row.put("soldValue", DashboardFormat.money(item.soldValue()));
            }
            items.add(row);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("sortBy", sortBy);
        data.put("type", type);
        data.put("items", items);
        return new Result(data, Links.report("meal-extras-sales", range, "groupBy", "ITEM", "type", type,
                "sortBy", "NET_PORTIONS"));
    }

    /** SET_MEAL, LIGHT_MEAL or BEVERAGE for meals (by catalogue group); EXTRA for extras. */
    private static String tag(ItemSales item) {
        if (!item.itemType().equals("MEAL")) {
            return "EXTRA";
        }
        if (item.group() == null) {
            return "SET_MEAL";
        }
        return switch (item.group()) {
            case "LIGHT_MEALS" -> "LIGHT_MEAL";
            case "BEVERAGES" -> "BEVERAGE";
            default -> "SET_MEAL";
        };
    }

    static String choice(String raw, String name, String fallback, List<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw DashboardParamException.of(name, name + " must be one of " + String.join(", ", allowed) + ".");
        }
        return value;
    }
}
