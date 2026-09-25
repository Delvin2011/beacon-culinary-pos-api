package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** GET is reference data for dropdowns — falls through to the default {@code authenticated()}
 * rule in {@code SecurityConfig} (any role), same as every other unmatched endpoint. Create and
 * update live under /admin/count-sheet-categories, which {@code AdminSecurityRules} opens to
 * STOCK_ADMIN and ADMIN alongside the Ingredient Master. */
@AllArgsConstructor
@RestController
public class CountSheetCategoryController {
    private final CountSheetCategoryService countSheetCategoryService;

    /** Active categories only by default; pass includeInactive=true for the management screen. */
    @GetMapping("/count-sheet-categories")
    public List<CountSheetCategoryDto> getAll(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return countSheetCategoryService.getAll(includeInactive);
    }

    @PostMapping("/admin/count-sheet-categories")
    public ResponseEntity<CountSheetCategoryDto> create(
            @Valid @RequestBody CreateCountSheetCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(countSheetCategoryService.create(request));
    }

    @PutMapping("/admin/count-sheet-categories/{id}")
    public CountSheetCategoryDto update(
            @PathVariable Long id, @Valid @RequestBody UpdateCountSheetCategoryRequest request) {
        return countSheetCategoryService.update(id, request);
    }

    @ExceptionHandler(CountSheetCategoryNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
