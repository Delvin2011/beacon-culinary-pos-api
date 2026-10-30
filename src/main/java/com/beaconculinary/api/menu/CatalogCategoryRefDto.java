package com.beaconculinary.api.menu;

/** A meal's or menu item's category: enough to label and sort it. */
public record CatalogCategoryRefDto(Long id, String name, int sortOrder) {
    static CatalogCategoryRefDto of(CatalogCategory category) {
        return category == null ? null : new CatalogCategoryRefDto(category.getId(), category.getName(), category.getSortOrder());
    }
}
