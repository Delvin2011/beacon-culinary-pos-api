package com.beaconculinary.api.reports;

/** Reports R1 §1.1: the fixed set of hub categories. The frontend shows only categories that
 * have at least one report the caller may run. */
public enum ReportCategory {
    DAILY_STATEMENTS,
    SALES,
    PAYMENTS_ACCOUNTS,
    CONTROLS_STAFF,
    KITCHEN_PLANNING,
    STOCK_INVENTORY
}
