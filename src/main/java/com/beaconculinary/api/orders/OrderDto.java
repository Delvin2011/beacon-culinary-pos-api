package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDto {
    private Long id;
    private Integer orderNumber;
    // O2b: the receipt's unique serial, yyyyMMdd-NNNN (ReceiptNumbers); derived, on every order.
    private String receiptNumber;
    private LocalDate orderDate;
    private Long shiftId;
    // O2: the till the sale was rung up on (its shift's code); null for the unregistered till and sales before O2.
    private String tillCode;
    // O2b: that till's name as it was when the shift opened; null when tillCode is null.
    private String tillName;
    private Long cashierId;
    // O2b: the name of the person who rang the order up.
    private String cashierName;
    private OrderStatus status;
    private OrderKind orderKind;
    private BigDecimal subtotal;
    private BigDecimal total;
    private BigDecimal originalTotal;
    private BigDecimal preDiscountTotal;
    private BigDecimal saleDiscount;
    // O2b: the VAT rate used and the VAT included in originalTotal (VatRule); null on orders from before O2b.
    private BigDecimal vatRate;
    private BigDecimal vatIncludedAmount;
    // null when no discount was given at sale.
    private OrderDiscountDto discount;
    // null for a sale.
    private OrderComplimentaryDto complimentary;
    private LocalDateTime createdAt;
    private boolean printFailed;
    private List<OrderLineDto> lines;
    // One per station the order needs (KITCHEN, BAR); empty for a cold-only order.
    private List<OrderStationDto> stations;
    private List<OrderAdjustmentDto> adjustments;
    private List<OrderPaymentDto> payments;
}
