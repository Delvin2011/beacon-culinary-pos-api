package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateGrvLineRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    // Optional — when present, quantityOrdered is derived from this line and must reference the
    // same ingredient as ingredientId above. Omitted means an ad-hoc receipt.
    private Long purchaseOrderLineId;

    @NotNull(message = "quantityReceived is required")
    @DecimalMin(value = "0.0001", message = "quantityReceived must be positive")
    private BigDecimal quantityReceived;

    // GRV VAT handling: the unit price exactly as written on the invoice — VAT-inclusive or
    // VAT-exclusive according to the GRV's pricesIncludeVat.
    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.00", message = "unitPrice cannot be negative")
    private BigDecimal unitPrice;

    // Optional; falls back to the ingredient's default VAT code.
    private VatCode vatCode;

    // Retired: replaced by unitPrice + pricesIncludeVat. Accepted here only so a request that still
    // sends it is refused with a clear 400 instead of being silently ignored.
    private BigDecimal costPerUnit;
}
