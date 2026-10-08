package com.beaconculinary.api.reports;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports R1 §1.5, the Java side of the definitions layer: grouped queries over the
 * v_shift_facts / v_order_facts / v_adjustment_facts views (V85), which hold every business rule.
 * Everything aggregates in SQL; no orders are loaded into memory. Ranges filter on the raw UTC
 * opened_at of the relevant shift so the predicate uses idx_shifts_opened_at.
 */
@Component
@AllArgsConstructor
public class ReportFacts {
    private final NamedParameterJdbcTemplate jdbc;

    /** Gross, reductions and net by day of sale (or by the order's hour), plus Σ orders.total
     * for the net-agrees-with-orders check. A null bucket means "the whole range". */
    public record SalesFacts(String bucket, long orders, long voidedOrRefunded, BigDecimal grossSales, BigDecimal voids,
                             BigDecimal refunds, BigDecimal discounts, BigDecimal extrasRemoved, BigDecimal netSales,
                             BigDecimal orderTotals) {
        public long netOrders() {
            return orders - voidedOrRefunded;
        }

        public BigDecimal reductions() {
            return voids.add(refunds).add(discounts).add(extrasRemoved);
        }
    }

    /** Collected by payment method, by day of sale. */
    public record CollectedFacts(String bucket, BigDecimal cash, BigDecimal card, BigDecimal account,
                                 BigDecimal collected, long splitOrders) {
    }

    /** Paid out by refund method, by day processed. */
    public record PaidOutFacts(String bucket, BigDecimal cashPaidOut, BigDecimal accountCredits) {
    }

    /** One shift with everything the cash-up shows, snapshot parts included (null for open or
     * legacy shifts). */
    public record ShiftFacts(long shiftId, LocalDate tradingDay, String status, BigDecimal openingFloat,
                             LocalDateTime openedAtUtc, LocalDateTime closedAtUtc, String ownerName,
                             BigDecimal closingCash, BigDecimal expectedCash, BigDecimal variance,
                             String varianceReasonCode, String varianceAuthorizedByName, String closedByName,
                             BigDecimal cashSalesAtClose, BigDecimal cashRefundsAtClose) {
        public boolean open() {
            return "OPEN".equals(status);
        }

        /** Closed before the V82 snapshot columns existed. */
        public boolean legacy() {
            return !open() && (cashSalesAtClose == null || cashRefundsAtClose == null);
        }
    }

    private static final String SALES_SELECT = """
            COUNT(*) AS orders,
            COALESCE(SUM(is_voided_or_refunded), 0) AS voided_or_refunded,
            COALESCE(SUM(gross_sales), 0) AS gross_sales,
            COALESCE(SUM(voids), 0) AS voids,
            COALESCE(SUM(refunds), 0) AS refunds,
            COALESCE(SUM(discounts), 0) AS discounts,
            COALESCE(SUM(extras_removed), 0) AS extras_removed,
            COALESCE(SUM(net_sales), 0) AS net_sales,
            COALESCE(SUM(order_total), 0) AS order_totals
            FROM v_order_facts
            WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to
            """;

    public List<SalesFacts> sales(ReportRange range, GroupBy groupBy) {
        var bucket = groupBy.bucketSql("day_of_sale", "sale_hour");
        var sql = "SELECT " + bucket + " AS bucket, " + SALES_SELECT + " GROUP BY " + bucket + " ORDER BY bucket";
        return jdbc.query(sql, rangeParams(range), (rs, i) -> salesFacts(rs, rs.getString("bucket")));
    }

    public SalesFacts salesTotals(ReportRange range) {
        return jdbc.queryForObject("SELECT " + SALES_SELECT, rangeParams(range), (rs, i) -> salesFacts(rs, null));
    }

    public List<CollectedFacts> collected(ReportRange range, GroupBy groupBy) {
        var bucket = groupBy.bucketSql("day_of_sale", "sale_hour");
        var sql = "SELECT " + bucket + " AS bucket,"
                + " COALESCE(SUM(cash_collected), 0) AS cash,"
                + " COALESCE(SUM(card_collected), 0) AS card,"
                + " COALESCE(SUM(account_collected), 0) AS account,"
                + " COALESCE(SUM(collected), 0) AS collected,"
                + " COALESCE(SUM(CASE WHEN payment_lines > 1 THEN 1 ELSE 0 END), 0) AS split_orders"
                + " FROM v_order_facts WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to"
                + " GROUP BY " + bucket + " ORDER BY bucket";
        return jdbc.query(sql, rangeParams(range), (rs, i) -> new CollectedFacts(rs.getString("bucket"),
                rs.getBigDecimal("cash"), rs.getBigDecimal("card"), rs.getBigDecimal("account"),
                rs.getBigDecimal("collected"), rs.getLong("split_orders")));
    }

    public List<PaidOutFacts> paidOut(ReportRange range, GroupBy groupBy) {
        var bucket = groupBy.bucketSql("day_processed", null);
        var sql = "SELECT " + bucket + " AS bucket,"
                + " COALESCE(SUM(cash_paid_out), 0) AS cash_paid_out,"
                + " COALESCE(SUM(account_credits), 0) AS account_credits"
                + " FROM v_adjustment_facts"
                + " WHERE processed_shift_opened_at_utc >= :from AND processed_shift_opened_at_utc < :to"
                + " GROUP BY " + bucket + " ORDER BY bucket";
        return jdbc.query(sql, rangeParams(range), (rs, i) -> new PaidOutFacts(rs.getString("bucket"),
                rs.getBigDecimal("cash_paid_out"), rs.getBigDecimal("account_credits")));
    }

    /** Cash collected on each shift's orders minus cash paid out by adjustments it processed,
     * for every shift opened in the range — the movement each drawer should show. */
    public Map<Long, BigDecimal> netCashMovementByShift(ReportRange range) {
        var movement = new HashMap<Long, BigDecimal>();
        jdbc.query("SELECT shift_id, COALESCE(SUM(cash_collected), 0) AS cash FROM v_order_facts"
                        + " WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to GROUP BY shift_id",
                rangeParams(range), rs -> {
                    movement.merge(rs.getLong("shift_id"), rs.getBigDecimal("cash"), BigDecimal::add);
                });
        jdbc.query("SELECT processed_shift_id, COALESCE(SUM(cash_paid_out), 0) AS paid_out FROM v_adjustment_facts"
                        + " WHERE processed_shift_opened_at_utc >= :from AND processed_shift_opened_at_utc < :to"
                        + " GROUP BY processed_shift_id",
                rangeParams(range), rs -> {
                    movement.merge(rs.getLong("processed_shift_id"), rs.getBigDecimal("paid_out").negate(), BigDecimal::add);
                });
        return movement;
    }

    /** Shifts opened in the range (by trading day), oldest first, with optional filters. */
    public List<ShiftFacts> shifts(ReportRange range, Long ownerId, String status, boolean varianceOnly) {
        var params = rangeParams(range);
        var sql = new StringBuilder("""
                SELECT sf.shift_id, sf.trading_day, s.status, s.opening_float, s.opened_at, s.closed_at,
                       ou.name AS owner_name, s.closing_cash, s.expected_cash, s.variance, s.variance_reason_code,
                       au.name AS authorized_by_name, cu.name AS closed_by_name,
                       s.cash_sales_at_close, s.cash_refunds_at_close
                FROM v_shift_facts sf
                JOIN shifts s ON s.id = sf.shift_id
                JOIN users ou ON ou.id = s.cashier_id
                LEFT JOIN users au ON au.id = s.variance_authorized_by
                LEFT JOIN users cu ON cu.id = s.closed_by
                WHERE sf.opened_at_utc >= :from AND sf.opened_at_utc < :to
                """);
        if (ownerId != null) {
            sql.append(" AND s.cashier_id = :ownerId");
            params.addValue("ownerId", ownerId);
        }
        if (status != null) {
            sql.append(" AND s.status = :status");
            params.addValue("status", status);
        }
        if (varianceOnly) {
            sql.append(" AND s.status = 'CLOSED' AND s.variance <> 0");
        }
        sql.append(" ORDER BY sf.opened_at_utc, sf.shift_id");

        return jdbc.query(sql.toString(), params, (rs, i) -> new ShiftFacts(
                rs.getLong("shift_id"),
                rs.getObject("trading_day", LocalDate.class),
                rs.getString("status"),
                rs.getBigDecimal("opening_float"),
                rs.getObject("opened_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getString("owner_name"),
                rs.getBigDecimal("closing_cash"),
                rs.getBigDecimal("expected_cash"),
                rs.getBigDecimal("variance"),
                rs.getString("variance_reason_code"),
                rs.getString("authorized_by_name"),
                rs.getString("closed_by_name"),
                rs.getBigDecimal("cash_sales_at_close"),
                rs.getBigDecimal("cash_refunds_at_close")));
    }

    private static MapSqlParameterSource rangeParams(ReportRange range) {
        return new MapSqlParameterSource().addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive());
    }

    private static SalesFacts salesFacts(ResultSet rs, String bucket) throws SQLException {
        return new SalesFacts(bucket, rs.getLong("orders"), rs.getLong("voided_or_refunded"),
                rs.getBigDecimal("gross_sales"), rs.getBigDecimal("voids"), rs.getBigDecimal("refunds"),
                rs.getBigDecimal("discounts"), rs.getBigDecimal("extras_removed"), rs.getBigDecimal("net_sales"),
                rs.getBigDecimal("order_totals"));
    }
}
