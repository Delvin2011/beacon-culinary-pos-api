package com.beaconculinary.api.reports;

/** How a column's cell in the totals row was produced, always server-side: SUM of the column,
 * DERIVED from other totals (e.g. average order value = net sales / net orders, never an
 * average of averages), LABEL for descriptive text (e.g. "Total", "3 shifts"), or NONE. */
public enum ColumnTotal {
    SUM,
    DERIVED,
    LABEL,
    NONE
}
