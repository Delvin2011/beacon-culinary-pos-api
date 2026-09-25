package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Full replace — both fields required. active is a Boolean (not boolean) so an omitted value is
 * rejected instead of silently deactivating the category. */
@Data
public class UpdateCountSheetCategoryRequest {
    @NotBlank(message = "name is required")
    @Size(max = 50, message = "name must be at most 50 characters")
    private String name;

    @NotNull(message = "active is required")
    private Boolean active;
}
