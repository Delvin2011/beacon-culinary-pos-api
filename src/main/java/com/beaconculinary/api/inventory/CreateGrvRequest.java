package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateGrvRequest {
    @NotBlank(message = "invoiceNumber is required")
    private String invoiceNumber;

    // GRV VAT handling: chosen explicitly for every invoice — there is never a silent default.
    @NotNull(message = "pricesIncludeVat is required")
    private Boolean pricesIncludeVat;

    // Optional: the invoice total as printed (VAT-inclusive), for the invoice check.
    @DecimalMin(value = "0.00", message = "invoiceTotalInclVat cannot be negative")
    private BigDecimal invoiceTotalInclVat;

    // Optional — an ad-hoc receipt with no linked purchase order.
    private Long purchaseOrderId;

    @NotBlank(message = "supplierName is required")
    private String supplierName;

    private String note;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<CreateGrvLineRequest> lines;
}
