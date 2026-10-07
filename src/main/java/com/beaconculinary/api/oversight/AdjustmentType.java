package com.beaconculinary.api.oversight;

import com.beaconculinary.api.orders.OrderAdjustment;
import com.beaconculinary.api.orders.OrderAdjustmentAction;
import com.beaconculinary.api.orders.OrderAdjustmentScope;

/** POS Oversight A4: the four mutually exclusive adjustment kinds shown in the activity view;
 * totals use the same definitions. */
public enum AdjustmentType {
    VOID,
    REFUND,
    DISCOUNT,
    EXTRAS_REMOVED;

    public static AdjustmentType of(OrderAdjustment adjustment) {
        if (adjustment.getAction() == OrderAdjustmentAction.DISCOUNT) {
            return DISCOUNT;
        }
        if (adjustment.getScope() == OrderAdjustmentScope.EXTRAS_ONLY) {
            return EXTRAS_REMOVED;
        }
        return adjustment.getAction() == OrderAdjustmentAction.VOID ? VOID : REFUND;
    }
}
