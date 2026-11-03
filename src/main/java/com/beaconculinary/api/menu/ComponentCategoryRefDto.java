package com.beaconculinary.api.menu;

/** A component's category: enough to label and sort it. */
public record ComponentCategoryRefDto(Long id, String name, int sortOrder) {
    static ComponentCategoryRefDto of(ComponentCategory category) {
        return category == null ? null : new ComponentCategoryRefDto(category.getId(), category.getName(), category.getSortOrder());
    }
}
