package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.dashboard.DashboardCaller;
import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFacts.PendingApproval;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardPermission;
import com.beaconculinary.api.dashboard.DashboardRange;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.DashboardSettings.Feature;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.endofday.CloseDaySettings;
import com.beaconculinary.api.menu.StockStatus;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportFacts.ShiftFacts;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Needs attention: each rule below, kept only when the caller holds its permission (and its stage
 * is on), sorted by severity (RED, AMBER, BLUE, NEUTRAL) and then age (oldest first), at most 8,
 * with moreCount for the rest. Rules that look back respect R3's first trading day: days before
 * it are outside the end-of-day process, so their shifts are never reported as unsigned or their
 * days as not closed.
 */
@Component
@AllArgsConstructor
public class AttentionSection implements SectionHandler {
    private static final int LIMIT = 8;
    private static final List<String> SEVERITIES = List.of("RED", "AMBER", "BLUE", "NEUTRAL");

    private final DashboardFacts dashboardFacts;
    private final ReportFacts reportFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;
    private final CloseDaySettings closeDaySettings;
    private final SellThroughSection sellThrough;
    private final LightMealStockSection lightMealStock;

    @Override
    public DashboardSection section() {
        return DashboardSection.ATTENTION;
    }

    /** "Your requests" depend on who asks. */
    @Override
    public boolean perUser() {
        return true;
    }

    record Item(String key, String severity, String tag, String title, String detail, String actionLabel,
                String actionPath, Integer count, Instant since) {
        Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("key", key);
            map.put("severity", severity);
            map.put("tag", tag);
            map.put("title", title);
            map.put("detail", detail);
            map.put("action", Map.of("label", actionLabel, "path", actionPath));
            map.put("count", count);
            map.put("since", since);
            return map;
        }
    }

    @Override
    public Result compute(Request request) {
        var caller = request.caller();
        var items = new ArrayList<Item>();
        if (caller.has(DashboardPermission.REPORTS_CASH)) {
            items.addAll(overdueShifts());
        }
        if (settings.enabled(Feature.SIGN_OFF) && caller.has(DashboardPermission.SIGN_OFF_SHIFTS)) {
            unsignedShifts(items);
        }
        if (settings.enabled(Feature.SIGN_OFF) && caller.has(DashboardPermission.RUN_EOD)) {
            dayNotClosed(items);
        }
        pendingApprovals(caller, items);
        if (settings.enabled(Feature.LIGHT_MEAL_STOCK) && caller.has(DashboardPermission.STOCK_VIEW)) {
            lightMealStock(items);
        }
        if (caller.has(DashboardPermission.REPORTS_CASH)) {
            cashVariance(items);
        }
        if (caller.has(DashboardPermission.DAILY_PLANNING) && settings.enabled(Feature.REPORT_DEFINITIONS)) {
            slowPlannedMeals(items);
        }

        var now = calendar.now();
        items.sort(Comparator.comparingInt((Item item) -> SEVERITIES.indexOf(item.severity()))
                .thenComparing(item -> item.since() == null ? now : item.since()));
        var data = new LinkedHashMap<String, Object>();
        data.put("items", items.stream().limit(LIMIT).map(Item::toMap).toList());
        data.put("moreCount", Math.max(0, items.size() - LIMIT));
        return new Result(data, null);
    }

    /** A shift still open that opened on an earlier trading day. */
    private List<Item> overdueShifts() {
        var today = calendar.today();
        var items = new ArrayList<Item>();
        for (var shift : dashboardFacts.openShifts(calendar.nowUtc())) {
            if (!shift.tradingDay().isBefore(today)) {
                continue;
            }
            var from = shift.tradingDay().equals(today.minusDays(1)) ? "yesterday" : DashboardFormat.day(shift.tradingDay());
            items.add(new Item("OVERDUE_SHIFT", "AMBER", "Shift",
                    DashboardFormat.shortName(shift.ownerName()) + "’s shift is still open from " + from,
                    "Opened " + DashboardFormat.day(shift.tradingDay()) + " at " + DashboardFormat.time(shift.openedAtUtc())
                            + ". No new shift can start until it is closed.",
                    "Open POS Activity", "/pos-activity", null, DashboardFormat.instant(shift.openedAtUtc())));
        }
        return items;
    }

    /** Closed shifts with no current sign-off; the detail names up to 3. */
    private void unsignedShifts(List<Item> items) {
        var shifts = dashboardFacts.unsignedShifts(closeDaySettings.firstTradingDay(), calendar.nowUtc());
        if (shifts.isEmpty()) {
            return;
        }
        var n = shifts.size();
        var named = joinUpTo3(shifts, shift -> DashboardFormat.shortName(shift.ownerName()) + " ("
                + DashboardFormat.day(shift.tradingDay()) + ")");
        items.add(new Item("UNSIGNED_SHIFTS", "AMBER", "Sign-off",
                DashboardFormat.count(n, "closed shift is", "closed shifts are") + " not signed off",
                "Waiting: " + named + ".", "Sign off shifts", "/end-of-day", n,
                DashboardFormat.instant(shifts.get(0).closedAtUtc())));
    }

    /** Past trading days with shifts and no Z reading. */
    private void dayNotClosed(List<Item> items) {
        var days = dashboardFacts.unclosedDays(calendar.today(), closeDaySettings.firstTradingDay(), calendar.nowUtc());
        if (days.isEmpty()) {
            return;
        }
        var n = days.size();
        var title = n == 1 ? DashboardFormat.day(days.get(0)) + " is not closed"
                : n + " trading days are not closed";
        items.add(new Item("DAY_NOT_CLOSED", "AMBER", "End of day", title,
                "No Z reading yet for " + joinUpTo3(days, DashboardFormat::day) + ". Sign off its shifts and close the day.",
                "Close the day", "/end-of-day", n, startOf(days.get(0))));
    }

    /**
     * Stock requests and counts waiting for approval. An approver sees them all; anyone else sees
     * only their own (OWN_REQUESTS_WAITING), or everyone's when they may view all requests.
     */
    private void pendingApprovals(DashboardCaller caller, List<Item> items) {
        var pending = dashboardFacts.pendingApprovals(calendar.nowUtc());
        if (pending.isEmpty()) {
            return;
        }
        var now = calendar.now();
        if (caller.has(DashboardPermission.STOCK_APPROVE)) {
            var counts = pending.stream().filter(p -> p.kind().equals("COUNT")).count();
            var requests = pending.size() - counts;
            var parts = new ArrayList<String>();
            if (requests > 0) {
                parts.add(DashboardFormat.count(requests, "stock request", "stock requests"));
            }
            if (counts > 0) {
                parts.add(DashboardFormat.count(counts, "stock count", "stock counts"));
            }
            var oldest = DashboardFormat.instant(pending.get(0).requestedAtUtc());
            items.add(new Item("PENDING_APPROVALS", "BLUE", "Stock",
                    String.join(" and ", parts) + (pending.size() == 1 ? " is" : " are") + " waiting for approval",
                    "The oldest has waited " + age(oldest, now) + ".", "Review requests", "/stock/requests",
                    pending.size(), oldest));
            return;
        }
        var own = pending.stream()
                .filter(p -> caller.has(DashboardPermission.STOCK_REQUESTS_VIEW_ALL) || p.requestedBy() == caller.userId())
                .toList();
        if (own.isEmpty()) {
            return;
        }
        var oldest = own.get(0);
        var title = own.size() == 1 ? "Your " + kindLabel(oldest) + " is waiting for approval"
                : own.size() + " of your requests are waiting for approval";
        var sent = DashboardFormat.local(oldest.requestedAtUtc());
        items.add(new Item("OWN_REQUESTS_WAITING", "BLUE", "Stock", title,
                (own.size() == 1 ? "Sent " : "The oldest was sent ") + DashboardFormat.day(sent.toLocalDate()) + " at "
                        + DashboardFormat.time(oldest.requestedAtUtc()) + ".",
                "View my requests", "/stock/requests", own.size(), DashboardFormat.instant(oldest.requestedAtUtc())));
    }

    private static String kindLabel(PendingApproval approval) {
        return switch (approval.kind()) {
            case "ISSUE" -> "Issue request";
            case "WASTE" -> "Waste request";
            default -> "stock count";
        };
    }

    /** Light meals the Kitchen can make none of (RED) or fewer than their low level of (AMBER). */
    private void lightMealStock(List<Item> items) {
        var flagged = lightMealStock.reviews().stream()
                .filter(review -> review.status() == StockStatus.OUT || review.status() == StockStatus.LOW)
                .toList();
        if (flagged.isEmpty()) {
            return;
        }
        var out = flagged.stream().filter(review -> review.status() == StockStatus.OUT).count();
        var low = flagged.size() - out;
        String title;
        if (out > 0) {
            title = DashboardFormat.count(out, "light meal is", "light meals are") + " out of stock"
                    + (low > 0 ? ", " + low + " running low" : "");
        } else {
            title = DashboardFormat.count(low, "light meal is", "light meals are") + " running low";
        }
        var detail = joinUpTo3(flagged, review -> review.name() + " ("
                + (review.status() == StockStatus.OUT ? "out" : review.unitsAvailable() + " left")
                + (review.limitingIngredient() == null ? "" : ", " + review.limitingIngredient()) + ")");
        items.add(new Item("LIGHT_MEAL_STOCK", out > 0 ? "RED" : "AMBER", "Light meals", title, detail + ".",
                "Open light meal stock", "/catalogue/light-meals", flagged.size(), null));
    }

    /** Closed shifts in the last 7 trading days with |variance| at or above the threshold. */
    private void cashVariance(List<Item> items) {
        var threshold = settings.varianceThreshold();
        var shifts = reportFacts.shifts(calendar.span(DashboardRange.SEVEN_DAYS), null, "CLOSED", true).stream()
                .filter(shift -> shift.variance() != null && shift.variance().abs().compareTo(threshold) >= 0)
                .sorted(Comparator.comparing(ShiftFacts::closedAtUtc, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        if (shifts.isEmpty()) {
            return;
        }
        var latest = shifts.get(0);
        var closed = latest.closedAtUtc() == null ? latest.tradingDay() : DashboardFormat.local(latest.closedAtUtc()).toLocalDate();
        items.add(new Item("CASH_VARIANCE", "NEUTRAL", "Cash",
                "Cash variance of " + DashboardFormat.rand(latest.variance()) + " on "
                        + DashboardFormat.shortName(latest.ownerName()) + "’s shift",
                "Closed " + DashboardFormat.day(closed)
                        + (latest.closedAtUtc() == null ? "" : " at " + DashboardFormat.time(latest.closedAtUtc())) + ". "
                        + DashboardFormat.count(shifts.size(), "shift", "shifts") + " in the last 7 trading days had a variance of "
                        + DashboardFormat.rand(threshold) + " or more.",
                "Open Cash-up & Variance", "/reports/cash-up-variance", shifts.size(),
                latest.closedAtUtc() == null ? null : DashboardFormat.instant(latest.closedAtUtc())));
    }

    /**
     * Planned meals selling below the threshold after the cut-off time. Only while trading (until
     * the end of opening hours): it prompts action during service, and after closing there is
     * none to take; the Planned meals card still marks them slow.
     */
    private void slowPlannedMeals(List<Item> items) {
        var time = calendar.localNow().toLocalTime();
        if (time.isBefore(settings.slowMealAfter()) || !time.isBefore(settings.openTo())) {
            return;
        }
        var slow = sellThrough.rows().stream().filter(SellThroughSection.Row::slow).toList();
        if (slow.isEmpty()) {
            return;
        }
        var detail = joinUpTo3(slow, row -> row.name() + " " + row.sellThroughPercent().stripTrailingZeros().toPlainString()
                + "% (" + row.sold() + " of " + row.planned() + ")");
        items.add(new Item("SLOW_PLANNED_MEALS", "AMBER", "Planning",
                DashboardFormat.count(slow.size(), "planned meal is", "planned meals are") + " selling slowly",
                detail + ".", "Open Daily Planning", "/daily-planning", slow.size(), null));
    }

    private static <T> String joinUpTo3(List<T> values, Function<T, String> label) {
        var shown = values.stream().limit(3).map(label).toList();
        var text = String.join(", ", shown);
        return values.size() > 3 ? text + " and " + (values.size() - 3) + " more" : text;
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant();
    }

    /** "45 min", "3 h 10 min", "2 days". */
    private static String age(Instant since, Instant now) {
        var minutes = Math.max(0, Duration.between(since, now).toMinutes());
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes < 24 * 60) {
            return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
        }
        var days = minutes / (24 * 60);
        return DashboardFormat.count(days, "day", "days");
    }
}
