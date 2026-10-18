package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class IngredientRequirementsResponseDto {
    private List<IngredientRequirementDto> requirements;
    // Planned items that contribute nothing to requirements (no components, or no recipe), so the
    // planner can be warned before confirming; empty when every item is covered.
    private List<UncoveredPlanItemDto> uncovered;
}
