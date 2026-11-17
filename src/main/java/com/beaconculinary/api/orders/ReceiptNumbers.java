package com.beaconculinary.api.orders;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * O2b: the receipt's unique serial, {@code yyyyMMdd-NNNN} from the order's date and number (padded
 * to 4 digits; longer numbers keep all their digits). Unique because IX_orders_date_number makes
 * (order_date, order_number) unique. Derived, never stored, so the format can change freely.
 */
public final class ReceiptNumbers {
    private ReceiptNumbers() {
    }

    public static String of(LocalDate orderDate, Integer orderNumber) {
        if (orderDate == null || orderNumber == null) {
            return null;
        }
        return orderDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", orderNumber);
    }
}
