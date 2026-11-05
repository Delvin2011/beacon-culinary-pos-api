package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardRange;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportRange;
import com.beaconculinary.api.reports.ReportValues;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The basket. Average order = net sales ÷ net orders (Sales Summary). series = that daily figure
 * over the last 7 trading days. Items per order = meal and beverage units ÷ orders, and extras
 * attach = orders with a planned meal and at least one extra ÷ orders with a planned meal, both
 * over sales orders that were not voided (extras aren't items).
 */
@Component
@AllArgsConstructor
public class BasketSection implements SectionHandler {
    private final ReportFacts facts;
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;

    @Override
    public DashboardSection section() {
        return DashboardSection.BASKET;
    }

    @Override
    public Result compute(Request request) {
        var range = calendar.span(request.range());
        var sales = facts.salesTotals(range);
        var basket = dashboardFacts.basket(range);

        var series = new ArrayList<Map<String, Object>>();
        var days = calendar.series(DashboardRange.SEVEN_DAYS);
        if (!days.isEmpty()) {
            var byDay = new HashMap<LocalDate, BigDecimal>();
            facts.sales(new ReportRange(days.get(0), days.get(days.size() - 1)), GroupBy.DAY).forEach(row ->
                    byDay.put(LocalDate.parse(row.bucket().trim()), ReportValues.divide(row.netSales(), row.netOrders())));
            for (var day : days) {
                var point = new LinkedHashMap<String, Object>();
                point.put("day", day.toString());
                point.put("value", byDay.getOrDefault(day, DashboardFormat.money(BigDecimal.ZERO)));
                series.add(point);
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("averageOrder", ReportValues.divide(sales.netSales(), sales.netOrders()));
        data.put("series", series);
        data.put("itemsPerOrder", basket.orders() == 0 ? null
                : BigDecimal.valueOf(basket.mealUnits()).divide(BigDecimal.valueOf(basket.orders()), 1, RoundingMode.HALF_UP));
        data.put("extrasAttachPercent", DashboardFormat.percent(BigDecimal.valueOf(basket.plannedOrdersWithExtra()),
                BigDecimal.valueOf(basket.ordersWithPlannedMeal())));
        return new Result(data, Links.report("sales-summary", range, "groupBy", GroupBy.DAY.name()));
    }
}
