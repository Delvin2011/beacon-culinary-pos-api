package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The dashboard's own grouped queries. Each reads the shared definitions (v_shift_facts,
 * v_order_facts, v_order_line_facts, v_account_movements) and never re-derives what an order,
 * a sale or a sold unit is: "net units" are always the NET bucket of v_order_line_facts, and
 * orders are always v_order_facts' sales orders. Ranges filter on the shift's raw UTC opened_at,
 * like the reports, so they hit idx_shifts_opened_at. Operational queries (now, waiting,
 * attention) read the live tables and ignore anything later than "now".
 */
@Component
@AllArgsConstructor
public class DashboardFacts {
    private final NamedParameterJdbcTemplate jdbc;

    private static MapSqlParameterSource range(ReportRange range) {
        return new MapSqlParameterSource().addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive());
    }

    // --- Sales ---------------------------------------------------------------------------------

    /** Sales orders with a discount at sale. */
    public long discountedOrders(ReportRange range) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM v_order_facts
                WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to AND discounts_at_sale > 0""",
                range(range), Long.class);
    }

    /** One trading day (with no hour when it had no sales orders) and its sales orders in an hour. */
    public record DayHour(LocalDate day, Integer hour, long orders) {
    }

    /**
     * Sales orders by trading day and hour of day, plus every trading day in the range with no
     * sales orders at all (hour null), in one grouped query: the heatmap and the "typical"
     * busy-hour figures, which average over trading days, not calendar days.
     */
    public List<DayHour> ordersByDayAndHour(ReportRange range, Timestamp nowUtc) {
        return jdbc.query("""
                WITH days AS (
                    SELECT DISTINCT trading_day FROM v_shift_facts
                    WHERE opened_at_utc >= :from AND opened_at_utc < :to AND opened_at_utc <= :now
                ), cells AS (
                    SELECT day_of_sale, sale_hour, COUNT(*) AS orders FROM v_order_facts
                    WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to
                    GROUP BY day_of_sale, sale_hour
                )
                SELECT d.trading_day, c.sale_hour, COALESCE(c.orders, 0) AS orders
                FROM days d LEFT JOIN cells c ON c.day_of_sale = d.trading_day""",
                range(range).addValue("now", nowUtc),
                (rs, i) -> new DayHour(rs.getObject("trading_day", LocalDate.class),
                        (Integer) rs.getObject("sale_hour"), rs.getLong("orders")));
    }

    /** One catalog item's net units and sold value (net units × order-time price, before
     * discounts). group is the meal's catalogue group (null for extras). */
    public record ItemSales(String itemType, long catalogId, String name, String group, long netUnits,
                            BigDecimal soldValue) {
    }

    /** Every item sold in the range, across meal periods. itemType narrows to MEAL or EXTRA. */
    public List<ItemSales> itemSales(ReportRange range, String itemType) {
        var params = range(range);
        var filter = "";
        if (itemType != null) {
            filter = " AND f.item_type = :itemType";
            params.addValue("itemType", itemType);
        }
        return jdbc.query("""
                SELECT f.item_type, f.catalog_id, MAX(f.item_name) AS item_name, MAX(cat.group_code) AS group_code,
                       SUM(CASE WHEN f.unit_bucket = 'NET' THEN f.quantity ELSE 0 END) AS net_units,
                       SUM(CASE WHEN f.unit_bucket = 'NET' THEN f.gross ELSE 0 END) AS sold_value
                FROM v_order_line_facts f
                LEFT JOIN meal_catalog mc ON f.item_type = 'MEAL' AND mc.id = f.catalog_id
                LEFT JOIN catalog_categories cat ON cat.id = mc.category_id
                WHERE f.shift_opened_at_utc >= :from AND f.shift_opened_at_utc < :to""" + filter + """

                GROUP BY f.item_type, f.catalog_id""", params,
                (rs, i) -> new ItemSales(rs.getString("item_type"), rs.getLong("catalog_id"), rs.getString("item_name"),
                        rs.getString("group_code"), rs.getLong("net_units"), rs.getBigDecimal("sold_value")));
    }

    /** Sold value (NET units, before discounts) by item type, the meal's group and the line's
     * meal period. */
    public record MixRow(String itemType, String group, Long mealPeriodId, String mealPeriodName,
                         LocalTime mealPeriodStart, String periodAccepts, BigDecimal amount) {
    }

    public List<MixRow> salesMix(ReportRange range) {
        return jdbc.query("""
                SELECT f.item_type, cat.group_code, f.meal_period_id, mp.name AS meal_period_name,
                       mp.start_time AS meal_period_start, mp.accepts, SUM(f.gross) AS amount
                FROM v_order_line_facts f
                LEFT JOIN meal_catalog mc ON f.item_type = 'MEAL' AND mc.id = f.catalog_id
                LEFT JOIN catalog_categories cat ON cat.id = mc.category_id
                LEFT JOIN meal_periods mp ON mp.id = f.meal_period_id
                WHERE f.shift_opened_at_utc >= :from AND f.shift_opened_at_utc < :to AND f.unit_bucket = 'NET'
                GROUP BY f.item_type, cat.group_code, f.meal_period_id, mp.name, mp.start_time, mp.accepts""",
                range(range), (rs, i) -> new MixRow(rs.getString("item_type"), rs.getString("group_code"),
                        (Long) rs.getObject("meal_period_id", Long.class), rs.getString("meal_period_name"),
                        rs.getObject("meal_period_start", LocalTime.class), rs.getString("accepts"),
                        rs.getBigDecimal("amount")));
    }

    /** Basket figures over sales orders that were not voided. */
    public record BasketFacts(long orders, long mealUnits, long ordersWithPlannedMeal, long plannedOrdersWithExtra) {
    }

    public BasketFacts basket(ReportRange range) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS orders,
                       COALESCE(SUM(meal_units), 0) AS meal_units,
                       COALESCE(SUM(CASE WHEN planned_lines > 0 THEN 1 ELSE 0 END), 0) AS with_planned,
                       COALESCE(SUM(CASE WHEN planned_lines > 0 AND extra_lines > 0 THEN 1 ELSE 0 END), 0) AS planned_with_extra
                FROM (
                    SELECT f.order_id,
                           SUM(CASE WHEN f.item_type = 'MEAL' THEN f.quantity ELSE 0 END) AS meal_units,
                           SUM(CASE WHEN f.item_type = 'EXTRA' THEN 1 ELSE 0 END) AS extra_lines,
                           SUM(CASE WHEN f.item_type = 'MEAL' AND cat.group_code = 'SET_MEALS' THEN 1 ELSE 0 END) AS planned_lines
                    FROM v_order_line_facts f
                    LEFT JOIN meal_catalog mc ON f.item_type = 'MEAL' AND mc.id = f.catalog_id
                    LEFT JOIN catalog_categories cat ON cat.id = mc.category_id
                    WHERE f.shift_opened_at_utc >= :from AND f.shift_opened_at_utc < :to
                      AND f.unit_bucket NOT IN ('COMPLIMENTARY', 'VOIDED')
                    GROUP BY f.order_id
                ) per_order""", range(range),
                (rs, i) -> new BasketFacts(rs.getLong("orders"), rs.getLong("meal_units"), rs.getLong("with_planned"),
                        rs.getLong("planned_with_extra")));
    }

    // --- Planned meals ---------------------------------------------------------------------------

    /** A set meal planned for a day, in one meal period. */
    public record PlannedOption(long optionId, long catalogId, Long mealPeriodId, String name, int planned,
                                LocalTime mealPeriodStart) {
    }

    public List<PlannedOption> plannedSetMeals(LocalDate day) {
        return jdbc.query("""
                SELECT dmo.id, dmo.meal_catalog_id, dmo.meal_period_id, dmo.name, dmo.planned_portions,
                       mp.start_time AS meal_period_start
                FROM daily_meal_options dmo
                JOIN meal_catalog mc ON mc.id = dmo.meal_catalog_id
                JOIN catalog_categories cat ON cat.id = mc.category_id
                LEFT JOIN meal_periods mp ON mp.id = dmo.meal_period_id
                WHERE dmo.option_date = :day AND dmo.planned_portions IS NOT NULL AND cat.group_code = 'SET_MEALS'
                ORDER BY CASE WHEN mp.start_time IS NULL THEN 1 ELSE 0 END, mp.start_time, dmo.name, dmo.id""",
                new MapSqlParameterSource("day", java.sql.Date.valueOf(day)),
                (rs, i) -> new PlannedOption(rs.getLong("id"), rs.getLong("meal_catalog_id"),
                        (Long) rs.getObject("meal_period_id", Long.class), rs.getString("name"),
                        rs.getInt("planned_portions"), rs.getObject("meal_period_start", LocalTime.class)));
    }

    /** Net meal units of one order, for one catalog item and meal period, with when it was rung. */
    public record OrderUnits(long catalogId, Long mealPeriodId, long orderId, LocalDateTime createdAtUtc, long units) {
    }

    /** Net meal units per order on a range's orders, oldest order first. */
    public List<OrderUnits> netMealUnitsByOrder(ReportRange range, Timestamp nowUtc) {
        return jdbc.query("""
                SELECT f.catalog_id, f.meal_period_id, o.id AS order_id, o.created_at, SUM(f.quantity) AS units
                FROM v_order_line_facts f
                JOIN orders o ON o.id = f.order_id
                WHERE f.shift_opened_at_utc >= :from AND f.shift_opened_at_utc < :to
                  AND f.item_type = 'MEAL' AND f.unit_bucket = 'NET' AND o.created_at <= :now
                GROUP BY f.catalog_id, f.meal_period_id, o.id, o.created_at
                ORDER BY o.created_at, o.id""", range(range).addValue("now", nowUtc),
                (rs, i) -> new OrderUnits(rs.getLong("catalog_id"), (Long) rs.getObject("meal_period_id", Long.class),
                        rs.getLong("order_id"), rs.getObject("created_at", LocalDateTime.class), rs.getLong("units")));
    }

    // --- Accounts ------------------------------------------------------------------------------

    public record AccountCharges(long accountId, String name, BigDecimal amount) {
    }

    /** Charges (ACCOUNT payments, by day of sale) per account in the range. */
    public List<AccountCharges> accountCharges(ReportRange range) {
        return jdbc.query("""
                SELECT a.id, a.name, SUM(m.amount) AS amount
                FROM v_account_movements m
                JOIN accounts a ON a.id = m.account_id
                WHERE m.movement_type = 'CHARGE' AND m.entry_at_utc >= :from AND m.entry_at_utc < :to
                GROUP BY a.id, a.name""", range(range),
                (rs, i) -> new AccountCharges(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("amount")));
    }

    public long accountChargeOrders(ReportRange range) {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT order_id) FROM v_account_movements
                WHERE movement_type = 'CHARGE' AND entry_at_utc >= :from AND entry_at_utc < :to""",
                range(range), Long.class);
    }

    // --- Right now -----------------------------------------------------------------------------

    /** Open work at one station (or, before the bar, the whole order): how many rows are pending
     * or in progress, and when the oldest was paid. */
    public record StationLoad(String station, String status, long count, LocalDateTime oldestPaidUtc) {
    }

    public List<StationLoad> stationLoad(Timestamp nowUtc) {
        return jdbc.query("""
                SELECT ss.station, ss.status, COUNT(*) AS n, MIN(o.created_at) AS oldest
                FROM order_station_status ss
                JOIN orders o ON o.id = ss.order_id
                WHERE ss.status IN ('PENDING', 'IN_PROGRESS')
                  AND o.status IN ('PENDING', 'IN_PROGRESS', 'DONE') AND o.created_at <= :now
                GROUP BY ss.station, ss.status""", new MapSqlParameterSource("now", nowUtc),
                (rs, i) -> new StationLoad(rs.getString("station"), rs.getString("status"), rs.getLong("n"),
                        rs.getObject("oldest", LocalDateTime.class)));
    }

    /** Before station rows exist: every open order is the kitchen's. */
    public List<StationLoad> orderLoad(Timestamp nowUtc) {
        return jdbc.query("""
                SELECT 'KITCHEN' AS station, status, COUNT(*) AS n, MIN(created_at) AS oldest
                FROM orders WHERE status IN ('PENDING', 'IN_PROGRESS') AND created_at <= :now
                GROUP BY status""", new MapSqlParameterSource("now", nowUtc),
                (rs, i) -> new StationLoad(rs.getString("station"), rs.getString("status"), rs.getLong("n"),
                        rs.getObject("oldest", LocalDateTime.class)));
    }

    /** Orders DONE and not yet collected, and when the longest-waiting one became DONE. */
    public record ReadyToCollect(long count, LocalDateTime oldestDoneUtc) {
    }

    public ReadyToCollect readyToCollect(Timestamp nowUtc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS n, MIN(COALESCE(d.done_at, o.created_at)) AS oldest
                FROM orders o
                OUTER APPLY (
                    SELECT MAX(e.changed_at) AS done_at FROM order_status_events e
                    WHERE e.order_id = o.id AND e.station IS NULL AND e.to_status = 'DONE'
                ) d
                WHERE o.status = 'DONE' AND o.created_at <= :now""", new MapSqlParameterSource("now", nowUtc),
                (rs, i) -> new ReadyToCollect(rs.getLong("n"), rs.getObject("oldest", LocalDateTime.class)));
    }

    public record OpenShift(long shiftId, String ownerName, BigDecimal openingFloat, LocalDateTime openedAtUtc,
                            LocalDate tradingDay) {
    }

    /** Shifts open now (at most one by V40), oldest first. */
    public List<OpenShift> openShifts(Timestamp nowUtc) {
        return jdbc.query("""
                SELECT s.id, u.name, s.opening_float, s.opened_at, sf.trading_day
                FROM shifts s
                JOIN v_shift_facts sf ON sf.shift_id = s.id
                JOIN users u ON u.id = s.cashier_id
                WHERE s.status = 'OPEN' AND s.opened_at <= :now
                ORDER BY s.opened_at, s.id""", new MapSqlParameterSource("now", nowUtc),
                (rs, i) -> new OpenShift(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("opening_float"),
                        rs.getObject("opened_at", LocalDateTime.class), rs.getObject("trading_day", LocalDate.class)));
    }

    // --- Waiting -------------------------------------------------------------------------------

    /** One order's status timeline, from order_status_events (first time each step was reached). */
    public record OrderTimeline(long orderId, LocalDateTime paidUtc, LocalDateTime kitchenDoneUtc,
                                LocalDateTime barDoneUtc, LocalDateTime doneUtc, LocalDateTime collectedUtc) {
    }

    /** Every order rung in the range (sales and complimentary: both go through the kitchen). */
    public List<OrderTimeline> timelines(ReportRange range, Timestamp nowUtc) {
        return jdbc.query("""
                SELECT o.id, o.created_at, ev.kitchen_done, ev.bar_done, ev.done_at, ev.collected_at
                FROM orders o
                JOIN v_shift_facts sf ON sf.shift_id = o.shift_id
                OUTER APPLY (
                    SELECT MIN(CASE WHEN e.station = 'KITCHEN' AND e.to_status = 'DONE' THEN e.changed_at END) AS kitchen_done,
                           MIN(CASE WHEN e.station = 'BAR' AND e.to_status = 'DONE' THEN e.changed_at END) AS bar_done,
                           MIN(CASE WHEN e.station IS NULL AND e.to_status = 'DONE' THEN e.changed_at END) AS done_at,
                           MIN(CASE WHEN e.station IS NULL AND e.to_status = 'COLLECTED' THEN e.changed_at END) AS collected_at
                    FROM order_status_events e WHERE e.order_id = o.id
                ) ev
                WHERE sf.opened_at_utc >= :from AND sf.opened_at_utc < :to AND o.created_at <= :now""",
                range(range).addValue("now", nowUtc),
                (rs, i) -> new OrderTimeline(rs.getLong("id"), rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("kitchen_done", LocalDateTime.class), rs.getObject("bar_done", LocalDateTime.class),
                        rs.getObject("done_at", LocalDateTime.class), rs.getObject("collected_at", LocalDateTime.class)));
    }

    // --- Needs attention -----------------------------------------------------------------------

    /** Closed shifts whose latest sign-off row is not SIGNED_OFF (or that have none), from a
     * trading day on (all when null), oldest close first. */
    public record UnsignedShift(long shiftId, String ownerName, LocalDate tradingDay, LocalDateTime closedAtUtc) {
    }

    public List<UnsignedShift> unsignedShifts(LocalDate fromDay, Timestamp nowUtc) {
        var params = new MapSqlParameterSource("now", nowUtc);
        var sql = """
                SELECT s.id, u.name, sf.trading_day, s.closed_at
                FROM shifts s
                JOIN v_shift_facts sf ON sf.shift_id = s.id
                JOIN users u ON u.id = s.cashier_id
                OUTER APPLY (SELECT TOP 1 x.action FROM shift_signoffs x WHERE x.shift_id = s.id ORDER BY x.id DESC) so
                WHERE s.status = 'CLOSED' AND s.opened_at <= :now
                  AND (so.action IS NULL OR so.action <> 'SIGNED_OFF')""";
        if (fromDay != null) {
            sql += " AND s.opened_at >= :fromUtc";
            params.addValue("fromUtc", TradingCalendar.startUtc(fromDay));
        }
        return jdbc.query(sql + " ORDER BY s.closed_at, s.id", params,
                (rs, i) -> new UnsignedShift(rs.getLong("id"), rs.getString("name"), rs.getObject("trading_day", LocalDate.class),
                        rs.getObject("closed_at", LocalDateTime.class)));
    }

    /** Trading days before a day (and from a day on, when set) with no Z reading, oldest first. */
    public List<LocalDate> unclosedDays(LocalDate before, LocalDate fromDay, Timestamp nowUtc) {
        var params = new MapSqlParameterSource().addValue("before", TradingCalendar.startUtc(before)).addValue("now", nowUtc);
        var sql = """
                SELECT DISTINCT sf.trading_day FROM v_shift_facts sf
                WHERE sf.opened_at_utc < :before AND sf.opened_at_utc <= :now
                  AND NOT EXISTS (SELECT 1 FROM z_readings z WHERE z.trading_day = sf.trading_day)""";
        if (fromDay != null) {
            sql += " AND sf.opened_at_utc >= :fromUtc";
            params.addValue("fromUtc", TradingCalendar.startUtc(fromDay));
        }
        return jdbc.queryForList(sql + " ORDER BY sf.trading_day", params, java.sql.Date.class).stream()
                .map(java.sql.Date::toLocalDate).toList();
    }

    /** A stock request (ISSUE/WASTE) or stock take (COUNT) waiting for approval. */
    public record PendingApproval(String kind, long id, long requestedBy, LocalDateTime requestedAtUtc) {
    }

    public List<PendingApproval> pendingApprovals(Timestamp nowUtc) {
        return jdbc.query("""
                SELECT r.request_type AS kind, r.id, r.requested_by, r.requested_at FROM stock_requests r
                WHERE r.status IN ('REQUESTED', 'PARTIALLY_ACTIONED') AND r.requested_at <= :now
                UNION ALL
                SELECT 'COUNT', t.id, t.submitted_by, t.submitted_at FROM stock_takes t
                WHERE t.status = 'SUBMITTED' AND t.submitted_at <= :now
                ORDER BY requested_at""", new MapSqlParameterSource("now", nowUtc),
                (rs, i) -> new PendingApproval(rs.getString("kind"), rs.getLong("id"), rs.getLong("requested_by"),
                        rs.getObject("requested_at", LocalDateTime.class)));
    }
}
