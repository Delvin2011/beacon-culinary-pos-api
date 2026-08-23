package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ConfirmIngredientRequirementsResponseDto {
    private List<IngredientShortfallDto> shortfalls;
    // Stage 5 Revision — the Issuing Sheet this confirmation created, so the frontend can link
    // straight to it for authorization.
    private Long stockRequestId;
}
