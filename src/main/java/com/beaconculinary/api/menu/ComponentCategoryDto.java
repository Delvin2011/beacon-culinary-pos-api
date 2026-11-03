package com.beaconculinary.api.menu;

/** One row of GET /admin/component-categories. componentCount is active components in it. */
public record ComponentCategoryDto(Long id, String name, int sortOrder, boolean active, long componentCount) {
}
