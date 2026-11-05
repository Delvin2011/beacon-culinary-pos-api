package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFacts.MixRow;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.DashboardSettings.Feature;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where sales come from: item value before discounts over sold units only (the NET bucket of
 * v_order_line_facts: voided and refunded units, and extras removed, are out). Set meals and their
 * extras group by meal period (Breakfast, Lunch); with catalogue categories, light meals and
 * beverages get groups of their own, and extras on an all-day (light meal) period follow it.
 * It won't equal net sales when discounts were given, so the payload says BEFORE_DISCOUNTS.
 */
@Component
@AllArgsConstructor
public class SalesMixSection implements SectionHandler {
    private static final String LIGHT_MEALS = "LIGHT_MEALS";
    private static final String BEVERAGES = "BEVERAGES";

    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;

    @Override
    public DashboardSection section() {
        return DashboardSection.SALES_MIX;
    }

    private record Group(String key, String label, int order, LocalTime start) {
    }

    @Override
    public Result compute(Request request) {
        var range = calendar.span(request.range());
        var categories = settings.enabled(Feature.CATEGORIES);

        var amounts = new LinkedHashMap<Group, BigDecimal>();
        for (var row : dashboardFacts.salesMix(range)) {
            var group = group(row, categories);
            if (group != null) {
                amounts.merge(group, row.amount(), BigDecimal::add);
            }
        }
        // Meal periods in order of the day, then light meals, then beverages.
        var groups = amounts.entrySet().stream()
                .filter(entry -> entry.getValue().signum() != 0)
                .sorted(Comparator.comparing((Map.Entry<Group, BigDecimal> e) -> e.getKey().order())
                        .thenComparing(e -> e.getKey().start(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(e -> e.getKey().label()))
                .toList();
        var shares = DashboardFormat.shares(groups.stream().map(Map.Entry::getValue).toList());

        var rows = new ArrayList<Map<String, Object>>();
        var total = BigDecimal.ZERO;
        for (int i = 0; i < groups.size(); i++) {
            var entry = groups.get(i);
            var row = new LinkedHashMap<String, Object>();
            row.put("key", entry.getKey().key());
            row.put("label", entry.getKey().label());
            row.put("amount", DashboardFormat.money(entry.getValue()));
            row.put("percent", shares.get(i));
            rows.add(row);
            total = total.add(entry.getValue());
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("basis", "BEFORE_DISCOUNTS");
        data.put("total", DashboardFormat.money(total));
        data.put("groups", rows);
        return new Result(data, Links.report("meal-extras-sales", range, "groupBy", "MEAL_PERIOD"));
    }

    /** null: not shown (light meals and beverages before catalogue categories). */
    private static Group group(MixRow row, boolean categories) {
        var isMeal = row.itemType().equals("MEAL");
        if (isMeal && LIGHT_MEALS.equals(row.group()) || !isMeal && LIGHT_MEALS.equals(row.periodAccepts())) {
            return categories ? new Group(LIGHT_MEALS, "Light meals", 1, null) : null;
        }
        if (isMeal && BEVERAGES.equals(row.group())) {
            return categories ? new Group(BEVERAGES, "Beverages", 2, null) : null;
        }
        if (row.mealPeriodName() == null) {
            return new Group("SET_MEALS", "Set meals", 0, null);
        }
        var key = row.mealPeriodName().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return new Group(key, row.mealPeriodName(), 0, row.mealPeriodStart());
    }
}
