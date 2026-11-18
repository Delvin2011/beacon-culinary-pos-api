package com.beaconculinary.api.offline.exceptions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * O4: SQL for sale_exceptions (V111). Times are UTC LocalDateTime values; detail is JSON text. Runs
 * in the caller's transaction. Exceptions are never deleted; resolving fills in who, when and why.
 */
@Repository
public class SaleExceptionStore {
    private final JdbcTemplate jdbc;

    public SaleExceptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Resolution {
        RESOLVED, ACCOUNT_APPROVED
    }

    public record ExceptionRow(long id, long orderId, SaleExceptionCode code, SaleExceptionCode.Severity severity,
                               String detailJson, String status, Resolution resolution, String resolutionNote,
                               Long resolvedBy, String resolvedByName, LocalDateTime resolvedAt, LocalDateTime createdAt,
                               UUID deviceId, LocalDate tradingDay) {
        public boolean open() {
            return "OPEN".equals(status);
        }
    }

    /** List filters; null means any. */
    public record Filter(String status, SaleExceptionCode.Severity severity, SaleExceptionCode code, LocalDate tradingDay,
                         UUID deviceId) {
    }

    private static final RowMapper<ExceptionRow> ROW = (rs, i) -> new ExceptionRow(
            rs.getLong("id"), rs.getLong("order_id"), SaleExceptionCode.valueOf(rs.getString("code")),
            SaleExceptionCode.Severity.valueOf(rs.getString("severity")), rs.getString("detail"), rs.getString("status"),
            rs.getString("resolution") == null ? null : Resolution.valueOf(rs.getString("resolution")),
            rs.getString("resolution_note"), (Long) rs.getObject("resolved_by", Long.class), rs.getString("resolved_by_name"),
            rs.getObject("resolved_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class),
            uuid(rs.getString("device_id")), rs.getObject("trading_day", LocalDate.class));

    /** Records one exception (OPEN) and returns its id. */
    public long insert(long orderId, SaleExceptionCode code, String detailJson, UUID deviceId, LocalDate tradingDay) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO [sale_exceptions] ([order_id], [code], [severity], [detail], [device_id], [trading_day])
                    VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setString(2, code.name());
            statement.setString(3, code.severity().name());
            statement.setString(4, detailJson);
            statement.setString(5, deviceId == null ? null : deviceId.toString());
            statement.setObject(6, java.sql.Date.valueOf(tradingDay));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ExceptionRow> find(long id) {
        return jdbc.query("SELECT * FROM [sale_exceptions] WHERE [id] = ?", ROW, id).stream().findFirst();
    }

    public List<ExceptionRow> forOrder(long orderId) {
        return jdbc.query("SELECT * FROM [sale_exceptions] WHERE [order_id] = ? ORDER BY [id]", ROW, orderId);
    }

    public boolean exists(long orderId, SaleExceptionCode code) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM [sale_exceptions] WHERE [order_id] = ? AND [code] = ?",
                Integer.class, orderId, code.name());
        return count != null && count > 0;
    }

    /** Newest first, one page. */
    public List<ExceptionRow> list(Filter filter, int page, int size) {
        var where = new StringBuilder(" WHERE 1 = 1");
        var args = whereClause(filter, where);
        args.add(page * size);
        args.add(size);
        return jdbc.query("SELECT * FROM [sale_exceptions]" + where
                + " ORDER BY [created_at] DESC, [id] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", ROW, args.toArray());
    }

    public long count(Filter filter) {
        var where = new StringBuilder(" WHERE 1 = 1");
        var args = whereClause(filter, where);
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM [sale_exceptions]" + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** Open REVIEW exceptions per till (device id), for GET /admin/tills. */
    public java.util.Map<UUID, Integer> openReviewByDevice() {
        var counts = new java.util.HashMap<UUID, Integer>();
        jdbc.query("""
                SELECT [device_id], COUNT(*) AS [n] FROM [sale_exceptions]
                WHERE [status] = 'OPEN' AND [severity] = 'REVIEW' AND [device_id] IS NOT NULL
                GROUP BY [device_id]""", rs -> {
            counts.put(uuid(rs.getString("device_id")), rs.getInt("n"));
        });
        return counts;
    }

    /** Marks an OPEN exception resolved; false when it was already resolved (or doesn't exist). */
    public boolean resolve(long id, Resolution resolution, String note, long resolvedBy, String resolvedByName,
                           LocalDateTime at) {
        return jdbc.update("""
                UPDATE [sale_exceptions]
                SET [status] = 'RESOLVED', [resolution] = ?, [resolution_note] = ?, [resolved_by] = ?,
                    [resolved_by_name] = ?, [resolved_at] = ?
                WHERE [id] = ? AND [status] = 'OPEN'""",
                resolution.name(), note, resolvedBy, resolvedByName, at, id) == 1;
    }

    private static List<Object> whereClause(Filter filter, StringBuilder where) {
        var args = new ArrayList<Object>();
        if (filter.status() != null) {
            where.append(" AND [status] = ?");
            args.add(filter.status());
        }
        if (filter.severity() != null) {
            where.append(" AND [severity] = ?");
            args.add(filter.severity().name());
        }
        if (filter.code() != null) {
            where.append(" AND [code] = ?");
            args.add(filter.code().name());
        }
        if (filter.tradingDay() != null) {
            where.append(" AND [trading_day] = ?");
            args.add(java.sql.Date.valueOf(filter.tradingDay()));
        }
        if (filter.deviceId() != null) {
            where.append(" AND [device_id] = ?");
            args.add(filter.deviceId().toString());
        }
        return args;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value.toLowerCase(Locale.ROOT));
    }
}
