package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Planned set meals today: planned portions against net units sold (the NET bucket), when each
 * sold out (the order that took cumulative net units to the planned figure), and whether it is
 * slow (sell-through below the threshold after the cut-off time). Light meals and beverages are
 * never planned, so never listed.
 */
@Component
@AllArgsConstructor
public class SellThroughSection implements SectionHandler {
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;

    @Override
    public DashboardSection section() {
        return DashboardSection.SELL_THROUGH;
    }

    public record Row(String name, int planned, long sold, BigDecimal sellThroughPercent, String soldOutAt, boolean slow) {
    }

    @Override
    public Result compute(Request request) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : rows()) {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", row.name());
            map.put("planned", row.planned());
            map.put("sold", row.sold());
            map.put("sellThroughPercent", row.sellThroughPercent());
            map.put("soldOutAt", row.soldOutAt());
            map.put("slow", row.slow());
            rows.add(map);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("thresholdPercent", settings.slowMealThresholdPercent());
        data.put("afterTime", settings.slowMealAfter().toString());
        data.put("rows", rows);
        return new Result(data, null);
    }

    /** Today's rows; also read by Needs attention. */
    public List<Row> rows() {
        var today = calendar.today();
        var options = dashboardFacts.plannedSetMeals(today);
        if (options.isEmpty()) {
            return List.of();
        }
        var pastCutOff = !calendar.localNow().toLocalTime().isBefore(settings.slowMealAfter());

        // Running net units per (meal, meal period), order by order.
        var sold = new HashMap<String, Long>();
        var soldOutAt = new HashMap<String, String>();
        var planned = new HashMap<String, Integer>();
        options.forEach(option -> planned.merge(key(option.catalogId(), option.mealPeriodId()), option.planned(), Integer::sum));
        for (var units : dashboardFacts.netMealUnitsByOrder(new ReportRange(today, today), calendar.nowUtc())) {
            var key = key(units.catalogId(), units.mealPeriodId());
            var total = sold.merge(key, units.units(), Long::sum);
            var target = planned.get(key);
            if (target != null && target > 0 && total >= target && !soldOutAt.containsKey(key)) {
                soldOutAt.put(key, DashboardFormat.time(units.createdAtUtc()));
            }
        }

        var rows = new ArrayList<Row>();
        for (var option : options) {
            var key = key(option.catalogId(), option.mealPeriodId());
            var units = sold.getOrDefault(key, 0L);
            var percent = DashboardFormat.percent(BigDecimal.valueOf(units), BigDecimal.valueOf(option.planned()));
            var slow = pastCutOff && percent != null && percent.compareTo(settings.slowMealThresholdPercent()) < 0;
            rows.add(new Row(option.name(), option.planned(), units, percent, soldOutAt.get(key), slow));
        }
        return rows;
    }

    private static String key(long catalogId, Long mealPeriodId) {
        return catalogId + ":" + Objects.toString(mealPeriodId, "-");
    }
}
