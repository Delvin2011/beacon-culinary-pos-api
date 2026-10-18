package com.beaconculinary.api.reports;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalTime;
import java.util.List;

/**
 * Reports R2 §2, the Java side of v_order_line_facts and v_offered_items (V91): units rung by
 * bucket and gross as rung per item and meal period, alongside the days each was offered.
 * Aggregates in SQL; sold lines filter on the shift's raw UTC opened_at like R1, plan entries
 * on plan_date.
 */
@Component
@AllArgsConstructor
public class ItemSalesFacts {
    private final NamedParameterJdbcTemplate jdbc;

    /** One catalog item in one meal period: offered in the range, sold in it, or both. rung,
     * voided, refundedOrRemoved and gross cover sales orders; complimentary portions and their
     * list value (non-voided complimentary orders) are kept apart. */
    public record ItemFacts(String itemType, long catalogId, String itemName, String pluCode, long mealPeriodId,
                            String mealPeriodName, LocalTime mealPeriodStart, long daysOffered, long rung, long voided,
                            long refundedOrRemoved, BigDecimal gross, long complimentaryPortions,
                            BigDecimal complimentaryValue) {
        public long net() {
            return rung - voided - refundedOrRemoved;
        }
    }

    /**
     * Every item offered or sold in the range. itemType ("MEAL"/"EXTRA") and mealPeriodId
     * narrow both sides; null means all.
     */
    public List<ItemFacts> items(ReportRange range, String itemType, Long mealPeriodId) {
        var params = new MapSqlParameterSource()
                .addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive())
                .addValue("fromDay", Date.valueOf(range.from())).addValue("toDay", Date.valueOf(range.to()));
        var filters = "";
        if (itemType != null) {
            filters += " AND item_type = :itemType";
            params.addValue("itemType", itemType);
        }
        if (mealPeriodId != null) {
            filters += " AND meal_period_id = :mealPeriodId";
            params.addValue("mealPeriodId", mealPeriodId);
        }
        var sql = """
                WITH sold AS (
                    SELECT item_type, catalog_id, meal_period_id, MAX(plu_code) AS plu_code,
                           SUM(CASE WHEN unit_bucket <> 'COMPLIMENTARY' THEN quantity ELSE 0 END) AS rung,
                           SUM(CASE WHEN unit_bucket = 'VOIDED' THEN quantity ELSE 0 END) AS voided,
                           SUM(CASE WHEN unit_bucket = 'REFUNDED_OR_REMOVED' THEN quantity ELSE 0 END) AS refunded_or_removed,
                           SUM(CASE WHEN unit_bucket <> 'COMPLIMENTARY' THEN gross ELSE 0 END) AS gross,
                           SUM(CASE WHEN unit_bucket = 'COMPLIMENTARY' THEN quantity ELSE 0 END) AS complimentary_portions,
                           SUM(CASE WHEN unit_bucket = 'COMPLIMENTARY' THEN gross ELSE 0 END) AS complimentary_value
                    FROM v_order_line_facts
                    WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to""" + filters + """

                    GROUP BY item_type, catalog_id, meal_period_id
                ), offered AS (
                    SELECT item_type, catalog_id, meal_period_id, MAX(plu_code) AS plu_code,
                           COUNT(DISTINCT plan_date) AS days_offered
                    FROM v_offered_items
                    WHERE plan_date >= :fromDay AND plan_date <= :toDay""" + filters + """

                    GROUP BY item_type, catalog_id, meal_period_id
                ), items AS (
                    SELECT item_type, catalog_id, meal_period_id FROM sold
                    UNION
                    SELECT item_type, catalog_id, meal_period_id FROM offered
                )
                SELECT i.item_type, i.catalog_id, i.meal_period_id,
                       COALESCE(mc.name, cc.name) AS item_name,
                       COALESCE(s.plu_code, o.plu_code) AS plu_code,
                       mp.name AS meal_period_name, mp.start_time AS meal_period_start,
                       COALESCE(o.days_offered, 0) AS days_offered,
                       COALESCE(s.rung, 0) AS rung,
                       COALESCE(s.voided, 0) AS voided,
                       COALESCE(s.refunded_or_removed, 0) AS refunded_or_removed,
                       COALESCE(s.gross, 0) AS gross,
                       COALESCE(s.complimentary_portions, 0) AS complimentary_portions,
                       COALESCE(s.complimentary_value, 0) AS complimentary_value
                FROM items i
                LEFT JOIN sold s ON s.item_type = i.item_type AND s.catalog_id = i.catalog_id AND s.meal_period_id = i.meal_period_id
                LEFT JOIN offered o ON o.item_type = i.item_type AND o.catalog_id = i.catalog_id AND o.meal_period_id = i.meal_period_id
                LEFT JOIN meal_catalog mc ON i.item_type = 'MEAL' AND mc.id = i.catalog_id
                LEFT JOIN component_catalog cc ON i.item_type = 'EXTRA' AND cc.id = i.catalog_id
                JOIN meal_periods mp ON mp.id = i.meal_period_id
                """;
        return jdbc.query(sql, params, (rs, i) -> new ItemFacts(
                rs.getString("item_type"),
                rs.getLong("catalog_id"),
                rs.getString("item_name"),
                rs.getString("plu_code"),
                rs.getLong("meal_period_id"),
                rs.getString("meal_period_name"),
                rs.getObject("meal_period_start", LocalTime.class),
                rs.getLong("days_offered"),
                rs.getLong("rung"),
                rs.getLong("voided"),
                rs.getLong("refunded_or_removed"),
                rs.getBigDecimal("gross"),
                rs.getLong("complimentary_portions"),
                rs.getBigDecimal("complimentary_value")));
    }

    /** Gross as rung over every sales line in the range, with no item filters. */
    public BigDecimal grossAsRung(ReportRange range) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(gross), 0) FROM v_order_line_facts"
                        + " WHERE shift_opened_at_utc >= :from AND shift_opened_at_utc < :to AND unit_bucket <> 'COMPLIMENTARY'",
                new MapSqlParameterSource().addValue("from", range.fromUtc()).addValue("to", range.toUtcExclusive()),
                BigDecimal.class);
    }

    /** Meal periods for a filter's options, in the order of the day (all-day last). */
    public List<ParamOption> mealPeriodOptions() {
        return jdbc.query("SELECT id, name FROM meal_periods ORDER BY CASE WHEN start_time IS NULL THEN 1 ELSE 0 END, start_time, name",
                (rs, i) -> ParamOption.of(rs.getLong("id"), rs.getString("name")));
    }
}
