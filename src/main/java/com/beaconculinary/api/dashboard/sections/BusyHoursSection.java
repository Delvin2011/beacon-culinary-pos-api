package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Today's orders by hour (Sales Summary's hourly "Orders"), against the typical weekday: the
 * average over the same weekday's trading days in the last 28 days, excluding today. Hours run
 * across the opening hours, widened to any hour that had orders so the total always matches.
 */
@Component
@AllArgsConstructor
public class BusyHoursSection implements SectionHandler {
    private final ReportFacts facts;
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;

    @Override
    public DashboardSection section() {
        return DashboardSection.BUSY_HOURS;
    }

    @Override
    public Result compute(Request request) {
        var today = calendar.today();
        var todayRange = new ReportRange(today, today);
        var todayByHour = new HashMap<Integer, Long>();
        facts.sales(todayRange, GroupBy.HOUR).forEach(row -> todayByHour.put(hour(row.bucket()), row.orders()));

        // The same weekday's trading days in the 28 days before today.
        var history = new HashMap<Integer, Long>();
        var historyDays = new HashSet<LocalDate>();
        for (var cell : dashboardFacts.ordersByDayAndHour(new ReportRange(today.minusDays(28), today.minusDays(1)),
                calendar.nowUtc())) {
            if (cell.day().getDayOfWeek() != today.getDayOfWeek()) {
                continue;
            }
            historyDays.add(cell.day());
            if (cell.hour() != null) {
                history.merge(cell.hour(), cell.orders(), Long::sum);
            }
        }

        var first = settings.firstHour();
        var last = settings.lastHour();
        for (var hour : todayByHour.keySet()) {
            first = Math.min(first, hour);
            last = Math.max(last, hour);
        }

        var hours = new ArrayList<Map<String, Object>>();
        Map<String, Object> peak = null;
        long peakOrders = 0;
        for (int hour = first; hour <= last; hour++) {
            var orders = todayByHour.getOrDefault(hour, 0L);
            var row = new LinkedHashMap<String, Object>();
            row.put("hour", hour);
            row.put("today", orders);
            row.put("typical", historyDays.isEmpty() ? null
                    : BigDecimal.valueOf(history.getOrDefault(hour, 0L))
                            .divide(BigDecimal.valueOf(historyDays.size()), 1, RoundingMode.HALF_UP));
            hours.add(row);
            // The first hour with the highest count.
            if (orders > peakOrders) {
                peakOrders = orders;
                peak = new LinkedHashMap<>();
                peak.put("hour", hour);
                peak.put("orders", orders);
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("hours", hours);
        data.put("peak", peak);
        data.put("typicalBasis", basis(today, historyDays.size()));
        return new Result(data, Links.report("sales-summary", todayRange, "groupBy", GroupBy.HOUR.name()));
    }

    static int hour(String bucket) {
        return Integer.parseInt(bucket.trim().substring(0, 2));
    }

    /** "Average of the last 4 Wednesdays"; null with no history. */
    private static String basis(LocalDate today, int days) {
        if (days == 0) {
            return null;
        }
        var weekday = DashboardFormat.weekdayLong(today);
        return days == 1 ? "Last " + weekday : "Average of the last " + days + " " + weekday + "s";
    }
}
