package com.beaconculinary.api.orders;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderPaymentDto {
    private Long id;
    private PaymentMethod method;
    private BigDecimal amount;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal amountTendered;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal changeDue;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cardReference;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long accountId;
}
