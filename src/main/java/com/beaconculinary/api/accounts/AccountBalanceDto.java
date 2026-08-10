package com.beaconculinary.api.accounts;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class AccountBalanceDto {
    private BigDecimal totalCharged;
    private BigDecimal totalReversed;
    private BigDecimal totalPaid;
    private BigDecimal outstandingBalance;
}
