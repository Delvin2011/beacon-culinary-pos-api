package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/components/{id}/recipe")
public class RecipeController {
    private final RecipeService recipeService;

    @GetMapping
    public RecipeDto get(@PathVariable("id") Long componentCatalogId) {
        return recipeService.get(componentCatalogId);
    }

    @PutMapping
    public RecipeDto update(@PathVariable("id") Long componentCatalogId, @Valid @RequestBody UpdateRecipeRequest request) {
        return recipeService.update(componentCatalogId, request);
    }

    @ExceptionHandler(RecipeNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
