package com.beaconculinary.api.menu;

import java.util.List;

/** One node of GET /admin/catalog/categories. itemCount is active meals in this node, plus its
 * children's for a group row. assignable says whether a meal can sit here. */
public record CatalogCategoryDto(
        Long id,
        String name,
        CatalogGroup group,
        int sortOrder,
        boolean active,
        boolean topLevel,
        boolean assignable,
        long itemCount,
        List<CatalogCategoryDto> children) {
}
