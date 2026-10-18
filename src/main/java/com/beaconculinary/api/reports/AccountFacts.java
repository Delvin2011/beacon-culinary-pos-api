package com.beaconculinary.api.reports;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reports R2 §2C, the Java side of v_account_movements (V91). A movement is in a range when its
 * entry_day is; entry_at_utc carries that day as a UTC instant, so ranges are a half-open UTC
 * window like R1. Charges increase what an account owes; credits and payments reduce it.
 */
@Component
@AllArgsConstructor
public class AccountFacts {
    private final NamedParameterJdbcTemplate jdbc;

    /** One account over a range. closingByLedger is computed separately from the parts (every
     * movement up to the end of the range) for the balance-arithmetic check. */
    public record AccountBalance(long accountId, String name, boolean active, BigDecimal opening, BigDecimal charges,
                                 BigDecimal credits, BigDecimal payments, BigDecimal closingByLedger, long accountOrders,
                                 LocalDateTime lastPaymentUtc, long movements) {
        public BigDecimal closing() {
            return opening.add(charges).subtract(credits).subtract(payments);
        }
    }

    /** One movement on an account statement. */
    public record Movement(String type, LocalDateTime occurredAtUtc, String reference, String description, BigDecimal amount) {
    }

    private static final String SIGNED_MOVEMENTS = """
            (SELECT *, CASE WHEN movement_type = 'CHARGE' THEN amount ELSE -amount END AS signed
             FROM v_account_movements)""";

    /** Every account, active or not, including those with no movements. */
    public List<AccountBalance> balances(ReportRange range) {
        var sql = """
                SELECT a.id, a.name, a.active,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc < :from THEN m.signed END), 0) AS opening,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc >= :from AND m.entry_at_utc < :to
                                          AND m.movement_type = 'CHARGE' THEN m.amount END), 0) AS charges,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc >= :from AND m.entry_at_utc < :to
                                          AND m.movement_type = 'CREDIT' THEN m.amount END), 0) AS credits,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc >= :from AND m.entry_at_utc < :to
                                          AND m.movement_type = 'PAYMENT' THEN m.amount END), 0) AS payments,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc < :to THEN m.signed END), 0) AS closing_by_ledger,
                       COUNT(DISTINCT CASE WHEN m.entry_at_utc >= :from AND m.entry_at_utc < :to
                                            AND m.movement_type = 'CHARGE' THEN m.order_id END) AS account_orders,
                       MAX(CASE WHEN m.movement_type = 'PAYMENT' AND m.entry_at_utc < :to THEN m.occurred_at END) AS last_payment,
                       COALESCE(SUM(CASE WHEN m.entry_at_utc >= :from AND m.entry_at_utc < :to THEN 1 ELSE 0 END), 0) AS movements
                FROM accounts a
                LEFT JOIN """ + SIGNED_MOVEMENTS + """
                 m ON m.account_id = a.id
                GROUP BY a.id, a.name, a.active
                """;
        return jdbc.query(sql, rangeParams(range), (rs, i) -> new AccountBalance(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getBigDecimal("opening"),
                rs.getBigDecimal("charges"),
                rs.getBigDecimal("credits"),
                rs.getBigDecimal("payments"),
                rs.getBigDecimal("closing_by_ledger"),
                rs.getLong("account_orders"),
                rs.getObject("last_payment", LocalDateTime.class),
                rs.getLong("movements")));
    }

    /** What the account owed at the start of the range: every movement before it. */
    public BigDecimal openingBalance(long accountId, ReportRange range) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(signed), 0) FROM " + SIGNED_MOVEMENTS + " m"
                        + " WHERE m.account_id = :accountId AND m.entry_at_utc < :from",
                rangeParams(range).addValue("accountId", accountId), BigDecimal.class);
    }

    /** The account's movements in the range, in the order they happened. */
    public List<Movement> movements(long accountId, ReportRange range) {
        var sql = """
                SELECT movement_type, occurred_at, reference, description, amount
                FROM v_account_movements
                WHERE account_id = :accountId AND entry_at_utc >= :from AND entry_at_utc < :to
                ORDER BY occurred_at,
                         CASE movement_type WHEN 'CHARGE' THEN 0 WHEN 'CREDIT' THEN 1 ELSE 2 END,
                         source_id
                """;
        return jdbc.query(sql, rangeParams(range).addValue("accountId", accountId), (rs, i) -> new Movement(
                rs.getString("movement_type"),
                rs.getObject("occurred_at", LocalDateTime.class),
                rs.getString("reference"),
                rs.getString("description"),
                rs.getBigDecimal("amount")));
    }

    /** Accounts for a filter's options, A–Z; closed accounts are labelled "(inactive)". */
    public List<ParamOption> accountOptions() {
        return jdbc.query("SELECT id, name, active FROM accounts ORDER BY name, id",
                (rs, i) -> ParamOption.of(rs.getLong("id"),
                        rs.getString("name") + (rs.getBoolean("active") ? "" : " (inactive)")));
    }

    private static MapSqlParameterSource rangeParams(ReportRange range) {
        return new MapSqlParameterSource().addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive());
    }
}
