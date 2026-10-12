package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.UserRefDto;

import java.math.BigDecimal;

/** The discount at sale on an order response: what was entered (type, value), the rand amount
 * taken off, why, and which admin approved it. */
public record OrderDiscountDto(
        DiscountType type,
        BigDecimal value,
        BigDecimal amount,
        SaleDiscountReasonCode reasonCode,
        String note,
        UserRefDto authorizedBy) {
}
