package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewStockTakeRequest {
    @NotNull(message = "decision is required")
    private StockTakeDecision decision;

    private String note;
}
