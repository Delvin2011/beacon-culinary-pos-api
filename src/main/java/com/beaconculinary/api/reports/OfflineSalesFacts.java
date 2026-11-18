package com.beaconculinary.api.reports;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * O4: offline sales in the X, Z and cashier readings (contract section 9). A sale counts on its
 * trading day as the report views give it (booked_trading_day for a late sale). "Late offline cash"
 * is the cash of offline sales recorded after their shift had closed (flagged LATE_AFTER_SHIFT_CLOSE
 * when recorded): the shift's figures were
 * snapshotted at close and don't include it, so it is shown on its own line and left out of the
 * cash-ups reconciliation.
 */
@Component
public class OfflineSalesFacts {
    private final NamedParameterJdbcTemplate jdbc;

    public OfflineSalesFacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LateCash(int sales, BigDecimal amount) {
        public static final LateCash NONE = new LateCash(0, BigDecimal.ZERO);

        public boolean any() {
            return sales > 0;
        }
    }

    public record DayOffline(int sales, BigDecimal total, int openReviewExceptions, LateCash lateCash) {
    }

    // Late = the recorder found the shift already closed (LATE_AFTER_SHIFT_CLOSE, raised once and never
    // deleted; resolving it doesn't make the cash any less late).
    private static final String LATE = """
            FROM [orders] o
            JOIN [order_payments] p ON p.[order_id] = o.[id] AND p.[method] = 'CASH'
            WHERE o.[recorded_offline] = 1
              AND EXISTS (SELECT 1 FROM [sale_exceptions] e WHERE e.[order_id] = o.[id] AND e.[code] = 'LATE_AFTER_SHIFT_CLOSE')""";

    public DayOffline forDay(LocalDate day) {
        var params = new MapSqlParameterSource("day", java.sql.Date.valueOf(day));
        var sales = jdbc.queryForMap("""
                SELECT COUNT(*) AS [n], COALESCE(SUM(o.[original_total]), 0) AS [total]
                FROM [v_order_facts] f JOIN [orders] o ON o.[id] = f.[order_id]
                WHERE f.[day_of_sale] = :day AND o.[recorded_offline] = 1""", params);
        var open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM [sale_exceptions]
                WHERE [trading_day] = :day AND [status] = 'OPEN' AND [severity] = 'REVIEW'""", params, Integer.class);
        var late = jdbc.queryForMap("SELECT COUNT(DISTINCT o.[id]) AS [n], COALESCE(SUM(p.[amount]), 0) AS [amount] " + LATE
                + " AND o.[id] IN (SELECT [order_id] FROM [v_order_facts] WHERE [day_of_sale] = :day)", params);
        return new DayOffline(((Number) sales.get("n")).intValue(), ReportValues.money((BigDecimal) sales.get("total")),
                open == null ? 0 : open,
                new LateCash(((Number) late.get("n")).intValue(), ReportValues.money((BigDecimal) late.get("amount"))));
    }

    /** Late offline cash per shift; shifts without any are left out. */
    public Map<Long, LateCash> lateCashByShift(Collection<Long> shiftIds) {
        var result = new HashMap<Long, LateCash>();
        if (shiftIds.isEmpty()) {
            return result;
        }
        jdbc.query("SELECT o.[shift_id], COUNT(DISTINCT o.[id]) AS [n], SUM(p.[amount]) AS [amount] " + LATE
                        + " AND o.[shift_id] IN (:shifts) GROUP BY o.[shift_id]",
                new MapSqlParameterSource("shifts", shiftIds),
                rs -> {
                    result.put(rs.getLong("shift_id"), new LateCash(rs.getInt("n"), ReportValues.money(rs.getBigDecimal("amount"))));
                });
        return result;
    }

    public LateCash lateCashOfShift(long shiftId) {
        return lateCashByShift(java.util.List.of(shiftId)).getOrDefault(shiftId, LateCash.NONE);
    }
}
