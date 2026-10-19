package com.beaconculinary.api.endofday;

import com.beaconculinary.api.common.UtcTimestamps;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Reports R3: the insert-only z_readings table. There is deliberately no update or delete. */
@Component
@AllArgsConstructor
public class ZReadings {
    private final NamedParameterJdbcTemplate jdbc;

    public record Z(int zNumber, LocalDate tradingDay, String closedByName, Instant closedAt) {
        public String reference() {
            return reference(zNumber);
        }

        public static String reference(int zNumber) {
            return String.format("Z-%04d", zNumber);
        }
    }

    private static final String SELECT = """
            SELECT z.z_number, z.trading_day, u.name AS closed_by_name, z.closed_at
            FROM z_readings z JOIN users u ON u.id = z.closed_by
            """;

    public Optional<Z> forDay(LocalDate tradingDay) {
        return jdbc.query(SELECT + " WHERE z.trading_day = :day", new MapSqlParameterSource("day", java.sql.Date.valueOf(tradingDay)),
                (rs, i) -> z(rs)).stream().findFirst();
    }

    /** Every Z, newest first. */
    public List<Z> all() {
        return jdbc.query(SELECT + " ORDER BY z.z_number DESC", (rs, i) -> z(rs));
    }

    public Optional<String> snapshot(int zNumber) {
        return jdbc.queryForList("SELECT snapshot FROM z_readings WHERE z_number = :z", new MapSqlParameterSource("z", zNumber),
                String.class).stream().findFirst();
    }

    /** Closed days on or after a date (all when null), as a set of dates. */
    public List<LocalDate> closedDaysFrom(LocalDate from) {
        var params = new MapSqlParameterSource();
        var sql = "SELECT trading_day FROM z_readings";
        if (from != null) {
            sql += " WHERE trading_day >= :from";
            params.addValue("from", java.sql.Date.valueOf(from));
        }
        return jdbc.queryForList(sql, params, java.sql.Date.class).stream().map(java.sql.Date::toLocalDate).toList();
    }

    int nextNumber() {
        var max = jdbc.queryForObject("SELECT MAX(z_number) FROM z_readings WITH (UPDLOCK, HOLDLOCK)",
                new MapSqlParameterSource(), Integer.class);
        return max == null ? 1 : max + 1;
    }

    void insert(int zNumber, LocalDate tradingDay, long closedBy, Instant closedAt, int definitionVersion,
                BigDecimal grossSales, BigDecimal netSales, String snapshot) {
        jdbc.update("""
                INSERT INTO z_readings (z_number, trading_day, closed_by, closed_at, definition_version, gross_sales, net_sales, snapshot)
                VALUES (:z, :day, :closedBy, :closedAt, :version, :gross, :net, :snapshot)""",
                new MapSqlParameterSource()
                        .addValue("z", zNumber).addValue("day", java.sql.Date.valueOf(tradingDay))
                        .addValue("closedBy", closedBy)
                        .addValue("closedAt", java.sql.Timestamp.valueOf(UtcTimestamps.toUtc(closedAt)))
                        .addValue("version", definitionVersion).addValue("gross", grossSales).addValue("net", netSales)
                        .addValue("snapshot", snapshot));
    }

    private static Z z(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Z(rs.getInt("z_number"), rs.getObject("trading_day", LocalDate.class), rs.getString("closed_by_name"),
                UtcTimestamps.toInstant(rs.getObject("closed_at", LocalDateTime.class)));
    }
}
