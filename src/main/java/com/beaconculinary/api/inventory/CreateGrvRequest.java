package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateGrvRequest {
    @NotBlank(message = "invoiceNumber is required")
    private String invoiceNumber;

    // Optional — an ad-hoc receipt with no linked purchase order.
    private Long purchaseOrderId;

    @NotBlank(message = "supplierName is required")
    private String supplierName;

    private String note;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<CreateGrvLineRequest> lines;
}
