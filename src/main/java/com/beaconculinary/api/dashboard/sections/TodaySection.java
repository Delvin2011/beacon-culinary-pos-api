package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.DashboardSettings.Feature;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportFacts.SalesFacts;
import com.beaconculinary.api.reports.ReportRange;
import com.beaconculinary.api.reports.ReportValues;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The today tiles: Sales Summary's figures for today (R1 definitions), each of the first three
 * against the same weekday last week. While today is still trading (no Z yet) the comparison is
 * "same time": last week's orders rung at or before the current time of day.
 */
@Component
@AllArgsConstructor
public class TodaySection implements SectionHandler {
    private final ReportFacts facts;
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;
    private final ZReadings zReadings;

    @Override
    public DashboardSection section() {
        return DashboardSection.TODAY;
    }

    @Override
    public Result compute(Request request) {
        var today = calendar.today();
        var range = new ReportRange(today, today);
        var sales = facts.salesTotals(range);
        var base = Comparison.of(today, calendar, settings, zReadings, facts);

        var tiles = new ArrayList<Map<String, Object>>();
        var averageOrder = ReportValues.divide(sales.netSales(), sales.netOrders());
        tiles.add(compared("netSales", "Net sales", money(sales.netSales()), "MONEY", base,
                base.facts == null ? null : base.facts.netSales()));
        tiles.add(compared("orders", "Orders", sales.orders(), "COUNT", base,
                base.facts == null ? null : BigDecimal.valueOf(base.facts.orders())));
        tiles.add(compared("averageOrder", "Average order", averageOrder, "MONEY", base,
                base.facts == null || base.facts.netOrders() == 0 ? null
                        : ReportValues.divide(base.facts.netSales(), base.facts.netOrders())));

        var voidsRefunds = sales.voids().add(sales.refunds());
        var share = DashboardFormat.percent(voidsRefunds, sales.grossSales());
        var shareValue = share == null ? BigDecimal.ZERO.setScale(1) : share;
        tiles.add(tile("voidsRefunds", "Voids & refunds", money(voidsRefunds), "MONEY",
                shareValue + "% of gross sales · " + DashboardFormat.count(sales.voidedOrRefunded(), "order", "orders"),
                null, shareValue.compareTo(settings.voidRefundWatchPercent()) > 0 ? "WATCH" : "NEUTRAL"));

        if (settings.enabled(Feature.COMPLIMENTARY)) {
            var complimentary = facts.complimentaryTotals(range);
            tiles.add(tile("complimentary", "Complimentary", complimentary.orders(), "COUNT",
                    DashboardFormat.rand(complimentary.value()) + " at list price", null, "NEUTRAL"));
        }
        tiles.add(tile("discounts", "Discounts at sale", money(sales.discountsAtSale()), "MONEY",
                DashboardFormat.count(dashboardFacts.discountedOrders(range), "order", "orders"), null, "NEUTRAL"));

        var data = new LinkedHashMap<String, Object>();
        data.put("day", today.toString());
        data.put("compareDay", base.day.toString());
        data.put("compareLabel", base.label);
        data.put("tiles", tiles);
        return new Result(data, Links.report("sales-summary", range, "groupBy", GroupBy.DAY.name()));
    }

    private static Map<String, Object> compared(String key, String label, Object value, String type, Comparison base,
                                                BigDecimal baseValue) {
        var delta = base.facts == null ? null : DashboardFormat.delta(new BigDecimal(value.toString()), baseValue);
        var tone = delta == null ? "NEUTRAL" : delta >= 0 ? "POSITIVE" : "WATCH";
        return tile(key, label, value, type, base.label, delta, tone);
    }

    private static Map<String, Object> tile(String key, String label, Object value, String type, String secondary,
                                            Integer delta, String tone) {
        var tile = new LinkedHashMap<String, Object>();
        tile.put("key", key);
        tile.put("label", label);
        tile.put("value", value);
        tile.put("type", type);
        tile.put("secondary", secondary);
        tile.put("delta", delta);
        tile.put("tone", tone);
        return tile;
    }

    private static BigDecimal money(BigDecimal value) {
        return DashboardFormat.money(value);
    }

    /**
     * The same weekday a week earlier: its sales totals (null when it had no shifts), up to the
     * current time of day while the day being compared is still trading. Shared with the trend.
     */
    record Comparison(LocalDate day, SalesFacts facts, String label) {
        static Comparison of(LocalDate day, TradingCalendar calendar, DashboardSettings settings, ZReadings zReadings,
                             ReportFacts facts) {
            var base = day.minusDays(7);
            var sameTime = isStillTrading(day, calendar, settings, zReadings);
            var label = "vs last " + DashboardFormat.weekday(base) + (sameTime ? ", same time" : "");
            if (!calendar.isTradingDay(base)) {
                return new Comparison(base, null, label);
            }
            var range = new ReportRange(base, base);
            return new Comparison(base, sameTime ? facts.salesTotalsUntil(range, calendar.sameTimeOn(base))
                    : facts.salesTotals(range), label);
        }

        /** Today, and not closed with a Z reading. Earlier days are complete. */
        static boolean isStillTrading(LocalDate day, TradingCalendar calendar, DashboardSettings settings, ZReadings zReadings) {
            if (!day.equals(calendar.today())) {
                return false;
            }
            return !settings.enabled(Feature.SIGN_OFF) || zReadings.forDay(day).isEmpty();
        }
    }
}
