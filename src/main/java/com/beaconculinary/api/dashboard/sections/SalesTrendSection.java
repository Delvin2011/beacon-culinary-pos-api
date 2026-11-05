package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Net sales by trading day (Sales Summary grouped by day), each against the same weekday a week
 * earlier (same time for a day still trading; null when that day had no shifts).
 */
@Component
@AllArgsConstructor
public class SalesTrendSection implements SectionHandler {
    private final ReportFacts facts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;
    private final ZReadings zReadings;

    @Override
    public DashboardSection section() {
        return DashboardSection.SALES_TREND;
    }

    @Override
    public Result compute(Request request) {
        var today = calendar.today();
        var days = calendar.series(request.range());
        var data = new LinkedHashMap<String, Object>();
        var rows = new ArrayList<Map<String, Object>>();
        data.put("days", rows);
        if (days.isEmpty()) {
            return new Result(data, Links.report("sales-summary", new ReportRange(today, today), "groupBy", GroupBy.DAY.name()));
        }
        var first = days.get(0);
        var last = days.get(days.size() - 1);

        // One grouped query over the shown days and the week before them.
        var netByDay = new HashMap<LocalDate, BigDecimal>();
        facts.sales(new ReportRange(first.minusDays(7), last), GroupBy.DAY)
                .forEach(row -> netByDay.put(LocalDate.parse(row.bucket().trim()), row.netSales()));
        var tradingDays = calendar.tradingDaysBetween(first.minusDays(7), last);

        for (var day : days) {
            var base = day.minusDays(7);
            BigDecimal compare = null;
            if (tradingDays.contains(base)) {
                compare = TodaySection.Comparison.isStillTrading(day, calendar, settings, zReadings)
                        ? facts.salesTotalsUntil(new ReportRange(base, base), calendar.sameTimeOn(base)).netSales()
                        : netByDay.getOrDefault(base, BigDecimal.ZERO);
                compare = DashboardFormat.money(compare);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("day", day.toString());
            row.put("label", DashboardFormat.shortDay(day));
            row.put("netSales", DashboardFormat.money(netByDay.getOrDefault(day, BigDecimal.ZERO)));
            row.put("compare", compare);
            row.put("isToday", day.equals(today));
            rows.add(row);
        }
        return new Result(data, Links.report("sales-summary", new ReportRange(first, last), "groupBy", GroupBy.DAY.name()));
    }
}
