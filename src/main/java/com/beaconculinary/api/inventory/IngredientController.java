package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/ingredients")
public class IngredientController {
    private final IngredientService ingredientService;

    @GetMapping
    public List<IngredientDto> getAll() {
        return ingredientService.getAll();
    }

    @PostMapping
    public ResponseEntity<IngredientDto> create(@Valid @RequestBody CreateIngredientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingredientService.create(request));
    }

    @PutMapping("/{id}")
    public IngredientDto update(@PathVariable Long id, @Valid @RequestBody UpdateIngredientRequest request) {
        return ingredientService.update(id, request);
    }

    @GetMapping("/{id}/stock")
    public IngredientStockDto getStock(@PathVariable Long id) {
        return ingredientService.getStock(id);
    }

    @ExceptionHandler(IngredientNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
