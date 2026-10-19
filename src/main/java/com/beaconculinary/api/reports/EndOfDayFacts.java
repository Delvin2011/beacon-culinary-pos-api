package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.UtcTimestamps;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports R3: the per-shift and per-order queries behind the X, Cashier and Z readings and the
 * Tender Summary check list. Built on the same views as R1 (v_order_facts, v_adjustment_facts,
 * v_complimentary_facts), so every rule stays defined once.
 */
@Component
@AllArgsConstructor
public class EndOfDayFacts {
    private final NamedParameterJdbcTemplate jdbc;

    /** Sales orders sold in one shift (its takings). */
    public record ShiftTakings(long orders, long voidedOrRefunded, BigDecimal gross, BigDecimal discountsAtSale,
                               BigDecimal reductionsAfterPayment, BigDecimal net, BigDecimal cash, BigDecimal card,
                               BigDecimal account, BigDecimal collected) {
        public static final ShiftTakings NONE = new ShiftTakings(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** An adjustment processed in a shift. soldInShiftId differs from the shift for a cross-shift refund. */
    public record Processed(Instant at, int orderNumber, String type, BigDecimal amount, String refundMethod,
                            String reasonCode, String authorizedByName, long soldInShiftId) {
    }

    public record RungByOthers(long orders, BigDecimal collected, List<String> names) {
    }

    public record ReasonCount(String reasonCode, long orders, BigDecimal value) {
    }

    public record ShiftOption(long shiftId, String ownerName, Instant openedAt, Instant closedAt) {
    }

    /** One sales order with payments, for Tender Summary's check list. */
    public record CheckRow(long orderId, LocalDate day, int orderNumber, Instant createdAt, String rungBy, BigDecimal cash,
                           BigDecimal card, BigDecimal account, BigDecimal collected, String status) {
    }

    public record CheckTotals(long rows, BigDecimal cash, BigDecimal card, BigDecimal account, BigDecimal collected) {
    }

    private static final String TAKINGS_SELECT = """
            SELECT shift_id,
                   COUNT(*) AS orders,
                   COALESCE(SUM(is_voided_or_refunded), 0) AS voided_or_refunded,
                   COALESCE(SUM(gross_sales), 0) AS gross,
                   COALESCE(SUM(discounts_at_sale), 0) AS discounts_at_sale,
                   COALESCE(SUM(voids + refunds + extras_removed + legacy_discounts_after_payment), 0) AS reductions,
                   COALESCE(SUM(net_sales), 0) AS net,
                   COALESCE(SUM(cash_collected), 0) AS cash,
                   COALESCE(SUM(card_collected), 0) AS card,
                   COALESCE(SUM(account_collected), 0) AS account,
                   COALESCE(SUM(collected), 0) AS collected
            FROM v_order_facts
            """;

    /** Takings of every shift opened in the range, by shift id; shifts with no sales are absent. */
    public Map<Long, ShiftTakings> takingsByShift(ReportRange range) {
        var byShift = new HashMap<Long, ShiftTakings>();
        jdbc.query(TAKINGS_SELECT + " WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to GROUP BY shift_id",
                rangeParams(range), rs -> {
                    byShift.put(rs.getLong("shift_id"), takings(rs));
                });
        return byShift;
    }

    public ShiftTakings takings(long shiftId) {
        var rows = jdbc.query(TAKINGS_SELECT + " WHERE shift_id = :id GROUP BY shift_id",
                new MapSqlParameterSource("id", shiftId), (rs, i) -> takings(rs));
        return rows.isEmpty() ? ShiftTakings.NONE : rows.get(0);
    }

    /** Voids, refunds and extras removed processed in a shift, in time order. */
    public List<Processed> processedIn(long shiftId) {
        return jdbc.query("""
                SELECT a.created_at, o.order_number, af.adjustment_type, a.amount, a.refund_method, a.reason_code,
                       u.name AS authorized_by_name, o.shift_id AS sold_in_shift_id
                FROM order_adjustments a
                JOIN v_adjustment_facts af ON af.adjustment_id = a.id
                JOIN orders o ON o.id = a.order_id
                LEFT JOIN users u ON u.id = a.authorized_by
                WHERE a.shift_id = :id
                ORDER BY a.created_at, a.id""", new MapSqlParameterSource("id", shiftId), (rs, i) -> new Processed(
                UtcTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)), rs.getInt("order_number"),
                rs.getString("adjustment_type"), rs.getBigDecimal("amount"), rs.getString("refund_method"),
                rs.getString("reason_code"), rs.getString("authorized_by_name"), rs.getLong("sold_in_shift_id")));
    }

    /** Sales orders on a shift rung by someone other than its owner (e.g. a manager on a cashier's drawer). */
    public RungByOthers rungByOthers(long shiftId) {
        var names = new ArrayList<String>();
        var totals = new long[1];
        var collected = new BigDecimal[]{BigDecimal.ZERO};
        jdbc.query("""
                SELECT u.name, COUNT(*) AS orders, COALESCE(SUM(f.collected), 0) AS collected
                FROM orders o
                JOIN shifts s ON s.id = o.shift_id
                JOIN users u ON u.id = o.cashier_id
                JOIN v_order_facts f ON f.order_id = o.id
                WHERE o.shift_id = :id AND o.cashier_id <> s.cashier_id
                GROUP BY u.name
                ORDER BY u.name""", new MapSqlParameterSource("id", shiftId), rs -> {
            names.add(rs.getString("name"));
            totals[0] += rs.getLong("orders");
            collected[0] = collected[0].add(rs.getBigDecimal("collected"));
        });
        return new RungByOthers(totals[0], collected[0], names);
    }

    public List<ReasonCount> complimentaryByReason(ReportRange range) {
        return jdbc.query("""
                SELECT COALESCE(reason_code, 'OTHER') AS reason_code, COUNT(*) AS orders, COALESCE(SUM(list_value), 0) AS value
                FROM v_complimentary_facts
                WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to
                GROUP BY COALESCE(reason_code, 'OTHER')
                ORDER BY COALESCE(reason_code, 'OTHER')""", rangeParams(range),
                (rs, i) -> new ReasonCount(rs.getString("reason_code"), rs.getLong("orders"), rs.getBigDecimal("value")));
    }

    public ReportFacts.ComplimentaryFacts complimentary(long shiftId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS orders, COALESCE(SUM(list_value), 0) AS value FROM v_complimentary_facts WHERE shift_id = :id""",
                new MapSqlParameterSource("id", shiftId),
                (rs, i) -> new ReportFacts.ComplimentaryFacts(null, rs.getLong("orders"), rs.getBigDecimal("value")));
    }

    /** Shifts opened on or after a day, newest first, for a picker. */
    public List<ShiftOption> shiftsSince(LocalDate from) {
        return jdbc.query("""
                SELECT sf.shift_id, u.name AS owner_name, s.opened_at, s.closed_at
                FROM v_shift_facts sf
                JOIN shifts s ON s.id = sf.shift_id
                JOIN users u ON u.id = s.cashier_id
                WHERE sf.trading_day >= :from
                ORDER BY sf.opened_at_utc DESC, sf.shift_id DESC""",
                new MapSqlParameterSource("from", java.sql.Date.valueOf(from)), (rs, i) -> new ShiftOption(
                        rs.getLong("shift_id"), rs.getString("owner_name"),
                        UtcTimestamps.toInstant(rs.getObject("opened_at", LocalDateTime.class)),
                        UtcTimestamps.toInstant(rs.getObject("closed_at", LocalDateTime.class))));
    }

    private static final String CHECK_FROM = """
            FROM v_order_facts f
            JOIN orders o ON o.id = f.order_id
            JOIN users u ON u.id = o.cashier_id
            WHERE f.payment_lines > 0
              AND f.shift_opened_at_utc >= :from AND f.shift_opened_at_utc < :to
            """;

    /** One page of sales orders with payments, by day of sale and order number. */
    public List<CheckRow> checks(ReportRange range, Integer checkNumber, ReportParams.Paging paging) {
        var params = checkParams(range, checkNumber);
        var sql = "SELECT f.order_id, f.day_of_sale, o.order_number, o.created_at, u.name AS rung_by, f.cash_collected, "
                + "f.card_collected, f.account_collected, f.collected, o.status " + CHECK_FROM + checkFilter(checkNumber)
                + " ORDER BY f.day_of_sale, o.order_number, f.order_id";
        if (!paging.all()) {
            sql += " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";
            params.addValue("offset", paging.offset()).addValue("size", paging.size());
        }
        return jdbc.query(sql, params, (rs, i) -> new CheckRow(rs.getLong("order_id"),
                rs.getObject("day_of_sale", LocalDate.class), rs.getInt("order_number"),
                UtcTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)), rs.getString("rung_by"),
                rs.getBigDecimal("cash_collected"), rs.getBigDecimal("card_collected"), rs.getBigDecimal("account_collected"),
                rs.getBigDecimal("collected"), rs.getString("status")));
    }

    /** Totals over every matching check, not just one page. */
    public CheckTotals checkTotals(ReportRange range, Integer checkNumber) {
        return jdbc.queryForObject("SELECT COUNT(*) AS n, COALESCE(SUM(f.cash_collected), 0) AS cash, "
                        + "COALESCE(SUM(f.card_collected), 0) AS card, COALESCE(SUM(f.account_collected), 0) AS account, "
                        + "COALESCE(SUM(f.collected), 0) AS collected " + CHECK_FROM + checkFilter(checkNumber),
                checkParams(range, checkNumber), (rs, i) -> new CheckTotals(rs.getLong("n"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card"), rs.getBigDecimal("account"), rs.getBigDecimal("collected")));
    }

    private static String checkFilter(Integer checkNumber) {
        return checkNumber == null ? "" : " AND o.order_number = :checkNumber";
    }

    private static MapSqlParameterSource checkParams(ReportRange range, Integer checkNumber) {
        var params = rangeParams(range);
        if (checkNumber != null) {
            params.addValue("checkNumber", checkNumber);
        }
        return params;
    }

    private static ShiftTakings takings(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShiftTakings(rs.getLong("orders"), rs.getLong("voided_or_refunded"), rs.getBigDecimal("gross"),
                rs.getBigDecimal("discounts_at_sale"), rs.getBigDecimal("reductions"), rs.getBigDecimal("net"),
                rs.getBigDecimal("cash"), rs.getBigDecimal("card"), rs.getBigDecimal("account"), rs.getBigDecimal("collected"));
    }

    private static MapSqlParameterSource rangeParams(ReportRange range) {
        return new MapSqlParameterSource().addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive());
    }
}
