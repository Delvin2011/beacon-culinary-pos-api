package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Average sales orders per weekday and hour over the last 28 days before today (one grouped
 * query). Each cell divides by the number of trading days of that weekday, not by 4, so a
 * closed Monday doesn't pull the Monday row down. Weekdays with no trading days are left out.
 */
@Component
@AllArgsConstructor
public class HeatmapSection implements SectionHandler {
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;

    @Override
    public DashboardSection section() {
        return DashboardSection.HEATMAP;
    }

    @Override
    public Result compute(Request request) {
        var today = calendar.today();
        var range = new ReportRange(today.minusDays(28), today.minusDays(1));

        var days = new EnumMap<DayOfWeek, Set<LocalDate>>(DayOfWeek.class);
        var orders = new EnumMap<DayOfWeek, Map<Integer, Long>>(DayOfWeek.class);
        var first = settings.firstHour();
        var last = settings.lastHour();
        for (var cell : dashboardFacts.ordersByDayAndHour(range, calendar.nowUtc())) {
            var weekday = cell.day().getDayOfWeek();
            days.computeIfAbsent(weekday, d -> new HashSet<>()).add(cell.day());
            if (cell.hour() != null) {
                orders.computeIfAbsent(weekday, d -> new HashMap<>()).merge(cell.hour(), cell.orders(), Long::sum);
                first = Math.min(first, cell.hour());
                last = Math.max(last, cell.hour());
            }
        }

        var hours = new ArrayList<Integer>();
        for (int hour = first; hour <= last; hour++) {
            hours.add(hour);
        }
        var rows = new ArrayList<Map<String, Object>>();
        var max = BigDecimal.ZERO.setScale(1);
        for (var weekday : DayOfWeek.values()) {
            var tradingDays = days.get(weekday);
            if (tradingDays == null) {
                continue;
            }
            var byHour = orders.getOrDefault(weekday, Map.of());
            var cells = new ArrayList<BigDecimal>();
            for (var hour : hours) {
                var cell = BigDecimal.valueOf(byHour.getOrDefault(hour, 0L))
                        .divide(BigDecimal.valueOf(tradingDays.size()), 1, RoundingMode.HALF_UP);
                cells.add(cell);
                max = max.max(cell);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("weekday", weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT));
            row.put("label", weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            row.put("tradingDays", tradingDays.size());
            row.put("cells", cells);
            rows.add(row);
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("from", range.from().toString());
        data.put("to", range.to().toString());
        data.put("hours", hours);
        data.put("rows", rows);
        data.put("max", max);
        return new Result(data, Links.report("sales-summary", range, "groupBy", GroupBy.HOUR.name()));
    }
}
