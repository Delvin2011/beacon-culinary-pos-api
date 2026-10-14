package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Stage 5.2.6 — a same-day correction of a {@link Grv}. Every editable line must be named
 * (identified by {@link UpdateGrvLineRequest#getId()}) — lines can't be added or removed here,
 * so this list must cover exactly the GRV's existing lines, no more, no fewer. GRV VAT handling:
 * the inclusive/exclusive toggle and the printed total can be corrected too, and every derived
 * figure is recomputed. */
@Data
public class UpdateGrvRequest {
    @NotBlank(message = "invoiceNumber is required")
    private String invoiceNumber;

    @NotNull(message = "pricesIncludeVat is required")
    private Boolean pricesIncludeVat;

    @DecimalMin(value = "0.00", message = "invoiceTotalInclVat cannot be negative")
    private BigDecimal invoiceTotalInclVat;

    @NotBlank(message = "supplierName is required")
    private String supplierName;

    private String note;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<UpdateGrvLineRequest> lines;
}
