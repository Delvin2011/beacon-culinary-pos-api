package com.beaconculinary.api.support;

import com.beaconculinary.api.common.ClockConfig;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Reports R1 Part 3: the "day in the life" fixture, inserted directly so every timestamp is
 * exact (all times below are Johannesburg; they're stored as UTC like the app does). Closed
 * shifts carry the snapshot parts a real close would have stored.
 *
 * <p>Owners: Thandiwe = seeded Cashier A, Sipho = seeded Cashier B, Admin = seeded admin.
 */
public final class ReportFixture {
    public static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    public static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    private final JdbcTemplate jdbc;

    public long s1, s2, s3;
    public long o1, o2, o3, o4, o5, o6, o7, o8, o9;

    private ReportFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Removes everything the fixture (or any other test) left in the order and shift tables. */
    public static void clear(JdbcTemplate jdbc) {
        for (var table : new String[]{"order_status_events", "order_line_extras", "order_lines", "order_adjustments",
                "order_payments", "orders", "shifts"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** A helper for hand-built scenarios, with nothing seeded. */
    public static ReportFixture empty(JdbcTemplate jdbc) {
        return new ReportFixture(jdbc);
    }

    public static ReportFixture seed(JdbcTemplate jdbc, long thandiwe, long sipho, long admin) {
        var f = new ReportFixture(jdbc);

        f.s1 = f.closedShift(thandiwe, "200.00", utc(MONDAY, 7, 0), utc(MONDAY, 11, 0),
                "440.00", "440.00", "0.00", null, null, thandiwe, "290.00", "50.00");
        f.s2 = f.closedShift(sipho, "300.00", utc(MONDAY, 11, 5), utc(MONDAY, 15, 0),
                "260.00", "270.00", "-10.00", "CASH_COUNTING_ERROR", admin, sipho, "70.00", "100.00");
        f.s3 = f.openShift(admin, "100.00", utc(TUESDAY, 8, 0));

        f.o1 = f.order(f.s1, thandiwe, MONDAY, 1, 7, 20, "100.00", "100.00", "COLLECTED");
        f.payment(f.o1, "CASH", "100.00");

        f.o2 = f.order(f.s1, thandiwe, MONDAY, 2, 7, 45, "60.00", "60.00", "COLLECTED");
        f.payment(f.o2, "CARD", "60.00");

        f.o3 = f.order(f.s1, thandiwe, MONDAY, 3, 8, 10, "80.00", "80.00", "COLLECTED");
        f.payment(f.o3, "CASH", "50.00");
        f.payment(f.o3, "CARD", "30.00");

        f.o4 = f.order(f.s1, thandiwe, MONDAY, 4, 9, 30, "50.00", "0.00", "VOIDED");
        f.payment(f.o4, "CASH", "50.00");
        f.adjustment(f.o4, f.s1, "WHOLE_ORDER", "VOID", "50.00", "CASH", thandiwe, admin, utc(MONDAY, 9, 35));

        f.o5 = f.order(f.s1, thandiwe, MONDAY, 5, 10, 15, "90.00", "0.00", "REFUNDED");
        f.payment(f.o5, "CASH", "90.00");
        // Cross-shift: sold in S1, refunded while S2 held the till.
        f.adjustment(f.o5, f.s2, "WHOLE_ORDER", "REFUND", "90.00", "CASH", sipho, admin, utc(MONDAY, 11, 20));

        f.o6 = f.order(f.s2, sipho, MONDAY, 6, 11, 30, "120.00", "120.00", "COLLECTED");
        f.payment(f.o6, "ACCOUNT", "120.00");

        f.o7 = f.order(f.s2, sipho, MONDAY, 7, 12, 5, "70.00", "60.00", "COLLECTED");
        f.payment(f.o7, "CASH", "70.00");
        f.adjustment(f.o7, f.s2, "WHOLE_ORDER", "DISCOUNT", "10.00", "CASH", sipho, admin, utc(MONDAY, 12, 10));

        f.o8 = f.order(f.s3, admin, TUESDAY, 1, 8, 20, "40.00", "40.00", "DONE");
        f.payment(f.o8, "CASH", "40.00");

        f.o9 = f.order(f.s3, admin, TUESDAY, 2, 12, 40, "50.00", "35.00", "PENDING");
        f.payment(f.o9, "ACCOUNT", "50.00");
        f.adjustment(f.o9, f.s3, "EXTRAS_ONLY", "VOID", "15.00", "ACCOUNT_BALANCE", admin, admin, utc(TUESDAY, 12, 45));

        return f;
    }

    /** The stored UTC value for a Johannesburg local date and time. */
    public static Timestamp utc(LocalDate day, int hour, int minute) {
        return Timestamp.valueOf(day.atTime(hour, minute).atZone(ClockConfig.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    public long closedShift(long owner, String openingFloat, Timestamp openedAt, Timestamp closedAt, String counted,
                            String expected, String variance, String reasonCode, Long authorizedBy, long closedBy,
                            String cashSalesAtClose, String cashRefundsAtClose) {
        if (reasonCode == null) {
            return jdbc.queryForObject("""
                    INSERT INTO shifts (cashier_id, opening_float, opened_at, closed_at, status, closing_cash, expected_cash,
                                        variance, closed_by, cash_sales_at_close, cash_refunds_at_close)
                    OUTPUT INSERTED.id VALUES (?, ?, ?, ?, 'CLOSED', ?, ?, ?, ?, ?, ?)""", Long.class,
                    owner, new BigDecimal(openingFloat), openedAt, closedAt, new BigDecimal(counted), new BigDecimal(expected),
                    new BigDecimal(variance), closedBy, new BigDecimal(cashSalesAtClose), new BigDecimal(cashRefundsAtClose));
        }
        return jdbc.queryForObject("""
                INSERT INTO shifts (cashier_id, opening_float, opened_at, closed_at, status, closing_cash, expected_cash,
                                    variance, variance_reason_code, variance_authorized_by, closed_by,
                                    cash_sales_at_close, cash_refunds_at_close)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, 'CLOSED', ?, ?, ?, ?, ?, ?, ?, ?)""", Long.class,
                owner, new BigDecimal(openingFloat), openedAt, closedAt, new BigDecimal(counted), new BigDecimal(expected),
                new BigDecimal(variance), reasonCode, authorizedBy, closedBy,
                new BigDecimal(cashSalesAtClose), new BigDecimal(cashRefundsAtClose));
    }

    public long openShift(long owner, String openingFloat, Timestamp openedAt) {
        return jdbc.queryForObject("INSERT INTO shifts (cashier_id, opening_float, opened_at, status) OUTPUT INSERTED.id "
                + "VALUES (?, ?, ?, 'OPEN')", Long.class, owner, new BigDecimal(openingFloat), openedAt);
    }

    /** An order on a shift, rung at a Johannesburg local time on the given day. */
    public long order(long shiftId, long cashierId, LocalDate day, int orderNumber, int hour, int minute,
                      String originalTotal, String total, String status) {
        return orderAt(shiftId, cashierId, day, orderNumber, utc(day, hour, minute), originalTotal, total, status);
    }

    public long orderAt(long shiftId, long cashierId, LocalDate orderDate, int orderNumber, Timestamp createdAt,
                        String originalTotal, String total, String status) {
        return jdbc.queryForObject("""
                INSERT INTO orders (order_number, order_date, shift_id, cashier_id, status, subtotal, total, original_total,
                                    extras_adjusted, created_at)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)""", Long.class,
                orderNumber, java.sql.Date.valueOf(orderDate), shiftId, cashierId, status, new BigDecimal(originalTotal),
                new BigDecimal(total), new BigDecimal(originalTotal), createdAt);
    }

    public void payment(long orderId, String method, String amount) {
        jdbc.update("INSERT INTO order_payments (order_id, method, amount) VALUES (?, ?, ?)", orderId, method, new BigDecimal(amount));
    }

    public void adjustment(long orderId, long processedInShiftId, String scope, String action, String amount, String refundMethod,
                           long requestedBy, long authorizedBy, Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO order_adjustments (order_id, shift_id, scope, action, reason_code, amount, refund_method,
                                               requested_by, authorized_by, created_at)
                VALUES (?, ?, ?, ?, 'CUSTOMER_COMPLAINT', ?, ?, ?, ?, ?)""",
                orderId, processedInShiftId, scope, action, new BigDecimal(amount), refundMethod, requestedBy, authorizedBy, createdAt);
    }
}
