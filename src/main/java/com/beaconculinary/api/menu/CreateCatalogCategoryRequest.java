package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A new subcategory under "Light meals" (or "Beverages"). Its group is the parent's. */
@Data
public class CreateCatalogCategoryRequest {
    @NotNull(message = "parentId is required")
    private Long parentId;

    @NotBlank(message = "name is required")
    @Size(max = 60, message = "name must be at most 60 characters")
    private String name;

    private int sortOrder;
}
