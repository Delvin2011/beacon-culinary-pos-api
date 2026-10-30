package com.beaconculinary.api.support;

import com.beaconculinary.api.common.ClockConfig;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reports R1 Part 3: the "day in the life" fixture, inserted directly so every timestamp is
 * exact (all times below are Johannesburg; they're stored as UTC like the app does). Closed
 * shifts carry the snapshot parts a real close would have stored.
 *
 * <p>Owners: Thandiwe = seeded Cashier A, Sipho = seeded Cashier B, Admin = seeded admin.
 *
 * <p>Reports R2 adds what each order contained (meal and extras lines against a daily plan of
 * its own catalog items) and three accounts. None of it changes an R1 figure: the R1 views
 * don't read lines, and the pre-range account activity (Sat 26 / Sun 27 Sep) sits before the
 * default 7-day range (30 Sep – 6 Oct) that R1's defaults test runs.
 */
public final class ReportFixture {
    public static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    public static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    public static final LocalDate PRE_RANGE_SATURDAY = LocalDate.of(2026, 9, 26);
    public static final LocalDate PRE_RANGE_SUNDAY = LocalDate.of(2026, 9, 27);

    private static final List<String> MEALS = List.of("Breakfast Plate", "Pap & Stew", "Chicken & Rice", "Fish & Chips");
    private static final List<String> EXTRAS = List.of("Extra Egg", "Extra Gravy", "Side Salad", "Extra Chilli");

    private final JdbcTemplate jdbc;

    public long s1, s2, s3, sPreRange;
    public long o1, o2, o3, o4, o5, o6, o7, o8, o9, oPreRange;
    /** Complimentary: O10 a staff meal (Mon, S2), O11 voided while pending (Tue, S3). */
    public long o10, o11;
    /** Acme Logistics, Zulu Catering, Dormant Co. */
    public long a1, a2, a3;
    /** Catalog ids by item name. */
    public final Map<String, Long> catalog = new HashMap<>();
    public long breakfastId, lunchId;

    private ReportFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Removes everything the fixture (or any other test) left in the order, shift and account
     * tables, plus the fixture's own plan and catalog items (and any plan entries on its dates). */
    public static void clear(JdbcTemplate jdbc) {
        // R3: Z readings are insert-only (a trigger refuses deletes); only the test database's
        // cleanup lifts that, for its own delete.
        jdbc.execute("DISABLE TRIGGER trg_z_readings_insert_only ON z_readings; DELETE FROM z_readings; "
                + "ENABLE TRIGGER trg_z_readings_insert_only ON z_readings;");
        jdbc.update("DELETE FROM receipt_reprints");
        jdbc.update("DELETE FROM shift_signoffs");
        for (var table : new String[]{"order_status_events", "order_line_extras", "order_lines", "order_adjustments", "order_discounts",
                "order_complimentaries",
                "order_payments", "orders", "shifts", "account_payments", "accounts"}) {
            jdbc.update("DELETE FROM " + table);
        }
        var meals = inList(MEALS);
        var extras = inList(EXTRAS);
        jdbc.update("DELETE FROM daily_meal_options WHERE option_date BETWEEN ? AND ? OR meal_catalog_id IN "
                + "(SELECT id FROM meal_catalog WHERE name IN " + meals + ")", PRE_RANGE_SATURDAY, TUESDAY);
        jdbc.update("DELETE FROM daily_component_stock WHERE option_date BETWEEN ? AND ? OR component_catalog_id IN "
                + "(SELECT id FROM component_catalog WHERE name IN " + extras + ")", PRE_RANGE_SATURDAY, TUESDAY);
        jdbc.update("DELETE FROM meal_catalog WHERE name IN " + meals);
        jdbc.update("DELETE FROM component_catalog WHERE name IN " + extras);
    }

    private static String inList(List<String> names) {
        return names.stream().map(name -> "'" + name.replace("'", "''") + "'").collect(Collectors.joining(", ", "(", ")"));
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
                "260.00", "270.00", "-10.00", "CASH_COUNTING_ERROR", admin, sipho, "60.00", "90.00");
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

        // Amendment 1: a R10 discount at sale (MANAGER_GOODWILL) — the customer pays R60; no adjustment.
        f.o7 = f.discountedOrder(f.s2, sipho, MONDAY, 7, 12, 5, "70.00", "10.00", "COLLECTED", sipho, admin);
        f.payment(f.o7, "CASH", "60.00");

        f.o8 = f.order(f.s3, admin, TUESDAY, 1, 8, 20, "40.00", "40.00", "DONE");
        f.payment(f.o8, "CASH", "40.00");

        f.o9 = f.order(f.s3, admin, TUESDAY, 2, 12, 40, "50.00", "35.00", "PENDING");
        f.payment(f.o9, "ACCOUNT", "50.00");
        f.adjustment(f.o9, f.s3, "EXTRAS_ONLY", "VOID", "15.00", "ACCOUNT_BALANCE", admin, admin, utc(TUESDAY, 12, 45));

        f.seedLinesAndAccounts(thandiwe, sipho, admin);
        return f;
    }

    /** Reports R2: the plan, each order's lines, and the accounts. Prices include VAT; the meal
     * period of every line follows the plan entry it was sold from. */
    private void seedLinesAndAccounts(long thandiwe, long sipho, long admin) {
        breakfastId = jdbc.queryForObject("SELECT id FROM meal_periods WHERE name = 'Breakfast'", Long.class);
        lunchId = jdbc.queryForObject("SELECT id FROM meal_periods WHERE name = 'Lunch'", Long.class);
        mealCatalog("Breakfast Plate", "40.00");
        mealCatalog("Pap & Stew", "35.00");
        mealCatalog("Chicken & Rice", "60.00");
        mealCatalog("Fish & Chips", "55.00");
        componentCatalog("Extra Egg", "10.00");
        componentCatalog("Extra Gravy", "5.00");
        componentCatalog("Side Salad", "10.00");
        componentCatalog("Extra Chilli", "5.00");

        var plan = new HashMap<String, Long>();
        for (var day : List.of(MONDAY, TUESDAY)) {
            plan.put("Breakfast Plate " + day, mealOption("Breakfast Plate", breakfastId, day, "40.00"));
            plan.put("Pap & Stew " + day, mealOption("Pap & Stew", lunchId, day, "35.00"));
            plan.put("Extra Egg " + day, componentStock("Extra Egg", breakfastId, day, "10.00"));
            plan.put("Extra Gravy " + day, componentStock("Extra Gravy", lunchId, day, "5.00"));
            plan.put("Side Salad " + day, componentStock("Side Salad", lunchId, day, "10.00"));
            plan.put("Extra Chilli " + day, componentStock("Extra Chilli", lunchId, day, "5.00"));
        }
        plan.put("Chicken & Rice " + MONDAY, mealOption("Chicken & Rice", lunchId, MONDAY, "60.00"));
        plan.put("Fish & Chips " + TUESDAY, mealOption("Fish & Chips", lunchId, TUESDAY, "55.00"));
        var preRangePlate = mealOption("Breakfast Plate", breakfastId, PRE_RANGE_SATURDAY, "40.00");

        var o1Plate = line(o1, plan.get("Breakfast Plate " + MONDAY), 2, "40.00");
        extra(o1Plate, plan.get("Extra Egg " + MONDAY), 2, "10.00", false);
        var o2Plate = line(o2, plan.get("Breakfast Plate " + MONDAY), 1, "40.00");
        extra(o2Plate, plan.get("Extra Egg " + MONDAY), 2, "10.00", false);
        line(o3, plan.get("Breakfast Plate " + MONDAY), 2, "40.00");
        var o4Stew = line(o4, plan.get("Pap & Stew " + MONDAY), 1, "35.00");
        extra(o4Stew, plan.get("Extra Gravy " + MONDAY), 1, "5.00", false);
        extra(o4Stew, plan.get("Side Salad " + MONDAY), 1, "10.00", false);
        var o5Stew = line(o5, plan.get("Pap & Stew " + MONDAY), 2, "35.00");
        extra(o5Stew, plan.get("Side Salad " + MONDAY), 2, "10.00", false);
        line(o6, plan.get("Chicken & Rice " + MONDAY), 2, "60.00");
        line(o7, plan.get("Pap & Stew " + MONDAY), 2, "35.00");
        line(o8, plan.get("Breakfast Plate " + TUESDAY), 1, "40.00");
        // O9's extras are the R15 removed later.
        var o9Stew = line(o9, plan.get("Pap & Stew " + TUESDAY), 1, "35.00");
        extra(o9Stew, plan.get("Extra Gravy " + TUESDAY), 1, "5.00", true);
        extra(o9Stew, plan.get("Side Salad " + TUESDAY), 1, "10.00", true);
        jdbc.update("UPDATE orders SET extras_adjusted = 1 WHERE id = ?", o9);

        a1 = account("Acme Logistics", true);
        a2 = account("Zulu Catering", true);
        a3 = account("Dormant Co", true);

        // Before the range: a R200 account order on A1 in its own closed shift, and a R50 payment.
        sPreRange = closedShift(thandiwe, "100.00", utc(PRE_RANGE_SATURDAY, 8, 0), utc(PRE_RANGE_SATURDAY, 14, 0),
                "100.00", "100.00", "0.00", null, null, thandiwe, "0.00", "0.00");
        oPreRange = order(sPreRange, thandiwe, PRE_RANGE_SATURDAY, 1, 9, 15, "200.00", "200.00", "COLLECTED");
        line(oPreRange, preRangePlate, 5, "40.00");
        accountPayment(oPreRange, a1, "200.00");
        settlement(a1, "50.00", "Cash", utc(PRE_RANGE_SUNDAY, 10, 0));

        // In the range: O6 on A1, O9 (and its extras credit) on A2, and A1's R100 EFT on Tuesday.
        // O6 was approved by the admin; O9 predates admin approval of account payments, so it has none.
        jdbc.update("UPDATE order_payments SET account_id = ?, authorized_by = ? WHERE order_id = ? AND method = 'ACCOUNT'", a1, admin, o6);
        jdbc.update("UPDATE order_payments SET account_id = ? WHERE order_id = ? AND method = 'ACCOUNT'", a2, o9);
        jdbc.update("UPDATE order_adjustments SET account_id = ? WHERE order_id = ?", a2, o9);
        settlement(a1, "100.00", "EFT", utc(TUESDAY, 14, 0));

        seedComplimentary(plan, sipho, admin);
    }

    /** Complimentary meals: no payments, so no R1, tender or account figure changes. O10 is a
     * R45 staff meal (Pap & Stew + Side Salad) in S2; O11 is a R35 management meal voided while
     * pending, which counts nowhere. */
    private void seedComplimentary(Map<String, Long> plan, long sipho, long admin) {
        o10 = complimentaryOrder(s2, sipho, MONDAY, 8, 12, 30, "45.00", "COLLECTED", "STAFF_MEAL", admin);
        var o10Stew = line(o10, plan.get("Pap & Stew " + MONDAY), 1, "35.00");
        extra(o10Stew, plan.get("Side Salad " + MONDAY), 1, "10.00", false);

        o11 = complimentaryOrder(s3, admin, TUESDAY, 3, 9, 15, "35.00", "VOIDED", "MANAGEMENT", admin);
        line(o11, plan.get("Pap & Stew " + TUESDAY), 1, "35.00");
        adjustment(o11, s3, "WHOLE_ORDER", "VOID", "0.00", "CASH", admin, admin, utc(TUESDAY, 9, 20));
    }

    /** A complimentary order: list value in pre_discount_total, nothing paid, and its approval row. */
    public long complimentaryOrder(long shiftId, long cashierId, LocalDate day, int orderNumber, int hour, int minute,
                                   String listValue, String status, String reasonCode, long authorizedBy) {
        var value = new BigDecimal(listValue);
        var orderId = jdbc.queryForObject("""
                INSERT INTO orders (order_number, order_date, shift_id, cashier_id, status, order_kind, subtotal, total,
                                    original_total, pre_discount_total, sale_discount, extras_adjusted, created_at)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, 'COMPLIMENTARY', ?, 0, 0, ?, 0, 0, ?)""", Long.class,
                orderNumber, java.sql.Date.valueOf(day), shiftId, cashierId, status, value, value, utc(day, hour, minute));
        jdbc.update("""
                INSERT INTO order_complimentaries (order_id, reason_code, requested_by, authorized_by)
                VALUES (?, ?, ?, ?)""", orderId, reasonCode, cashierId, authorizedBy);
        return orderId;
    }

    public long mealCatalog(String name, String price) {
        var id = jdbc.queryForObject("INSERT INTO meal_catalog (name, price, category_id) OUTPUT INSERTED.id VALUES (?, ?, "
                + "(SELECT id FROM catalog_categories WHERE name = 'Set meals' AND parent_id IS NULL))", Long.class,
                name, new BigDecimal(price));
        catalog.put(name, id);
        return id;
    }

    public long componentCatalog(String name, String extraPrice) {
        var id = jdbc.queryForObject("INSERT INTO component_catalog (name, extra_price) OUTPUT INSERTED.id VALUES (?, ?)",
                Long.class, name, new BigDecimal(extraPrice));
        catalog.put(name, id);
        return id;
    }

    public long mealOption(String item, long mealPeriodId, LocalDate day, String price) {
        return jdbc.queryForObject("""
                INSERT INTO daily_meal_options (meal_period_id, meal_catalog_id, option_date, name, price, planned_portions,
                                                portions_remaining)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, 20, 20)""", Long.class,
                mealPeriodId, catalog.get(item), java.sql.Date.valueOf(day), item, new BigDecimal(price));
    }

    public long componentStock(String item, long mealPeriodId, LocalDate day, String price) {
        return jdbc.queryForObject("""
                INSERT INTO daily_component_stock (component_catalog_id, meal_period_id, option_date, extra_price,
                                                   buffer_quantity, buffer_remaining)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, 20, 20)""", Long.class,
                catalog.get(item), mealPeriodId, java.sql.Date.valueOf(day), new BigDecimal(price));
    }

    /** A meal line at the price snapshotted on the order. */
    public long line(long orderId, long mealOptionId, int quantity, String unitPrice) {
        var price = new BigDecimal(unitPrice);
        return jdbc.queryForObject("""
                INSERT INTO order_lines (order_id, daily_meal_option_id, unit_price, quantity, line_total)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?)""", Long.class,
                orderId, mealOptionId, price, quantity, price.multiply(BigDecimal.valueOf(quantity)));
    }

    /** An extras line on a meal line; adjusted = removed after payment. */
    public long extra(long lineId, long componentStockId, int quantity, String unitPrice, boolean adjusted) {
        var price = new BigDecimal(unitPrice);
        return jdbc.queryForObject("""
                INSERT INTO order_line_extras (order_line_id, daily_component_stock_id, price_delta, quantity, line_total, adjusted)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?)""", Long.class,
                lineId, componentStockId, price, quantity, price.multiply(BigDecimal.valueOf(quantity)), adjusted);
    }

    public long account(String name, boolean active) {
        return jdbc.queryForObject("INSERT INTO accounts (name, active) OUTPUT INSERTED.id VALUES (?, ?)", Long.class, name, active);
    }

    public void accountPayment(long orderId, long accountId, String amount) {
        jdbc.update("INSERT INTO order_payments (order_id, method, amount, account_id) VALUES (?, 'ACCOUNT', ?, ?)",
                orderId, new BigDecimal(amount), accountId);
    }

    /** A settlement received from an account holder (account_payments). */
    public long settlement(long accountId, String amount, String note, Timestamp createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO account_payments (account_id, amount, note, recorded_by, created_at)
                OUTPUT INSERTED.id VALUES (?, ?, ?, (SELECT MIN(id) FROM users WHERE role = 'ADMIN'), ?)""", Long.class,
                accountId, new BigDecimal(amount), note, createdAt);
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
                INSERT INTO orders (order_number, order_date, shift_id, cashier_id, status, subtotal, total, original_total, pre_discount_total,
                                    extras_adjusted, created_at)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)""", Long.class,
                orderNumber, java.sql.Date.valueOf(orderDate), shiftId, cashierId, status, new BigDecimal(originalTotal),
                new BigDecimal(total), new BigDecimal(originalTotal), new BigDecimal(originalTotal), createdAt);
    }

    /** An order with a fixed discount at sale: original_total = total = preDiscountTotal - saleDiscount,
     * plus its order_discounts row. */
    public long discountedOrder(long shiftId, long cashierId, LocalDate day, int orderNumber, int hour, int minute,
                                String preDiscountTotal, String saleDiscount, String status, long requestedBy, long authorizedBy) {
        var pre = new BigDecimal(preDiscountTotal);
        var discount = new BigDecimal(saleDiscount);
        var paid = pre.subtract(discount);
        var orderId = jdbc.queryForObject("""
                INSERT INTO orders (order_number, order_date, shift_id, cashier_id, status, subtotal, total, original_total,
                                    pre_discount_total, sale_discount, extras_adjusted, created_at)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)""", Long.class,
                orderNumber, java.sql.Date.valueOf(day), shiftId, cashierId, status, pre, paid, paid, pre, discount,
                utc(day, hour, minute));
        jdbc.update("""
                INSERT INTO order_discounts (order_id, discount_type, discount_value, amount, reason_code, requested_by, authorized_by)
                VALUES (?, 'FIXED_AMOUNT', ?, ?, 'MANAGER_GOODWILL', ?, ?)""", orderId, discount, discount, requestedBy, authorizedBy);
        return orderId;
    }

    public void payment(long orderId, String method, String amount) {
        jdbc.update("INSERT INTO order_payments (order_id, method, amount) VALUES (?, ?, ?)", orderId, method, new BigDecimal(amount));
    }

    /** A legacy after-payment DISCOUNT (the retired feature, which the API now refuses), as an old
     * database would hold it: the adjustment row plus the reduced orders.total. */
    public void legacyDiscount(long orderId, long processedInShiftId, String amount, long requestedBy, long authorizedBy,
                               Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO order_adjustments (order_id, shift_id, scope, action, reason_code, amount, refund_method,
                                               requested_by, authorized_by, discount_type, discount_value, created_at)
                VALUES (?, ?, 'WHOLE_ORDER', 'DISCOUNT', 'CUSTOMER_COMPLAINT', ?, 'CASH', ?, ?, 'FIXED_AMOUNT', ?, ?)""",
                orderId, processedInShiftId, new BigDecimal(amount), requestedBy, authorizedBy, new BigDecimal(amount), createdAt);
        jdbc.update("UPDATE orders SET total = total - ? WHERE id = ?", new BigDecimal(amount), orderId);
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
