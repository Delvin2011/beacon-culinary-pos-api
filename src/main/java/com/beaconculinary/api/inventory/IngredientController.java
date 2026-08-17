package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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

    /** CSV upload with NAME, UNIT, COUNT SHEET columns (header names are matched
     * case/whitespace-insensitively). Rows are upserted by name — an existing ingredient is
     * updated in place rather than duplicated. */
    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkIngredientImportResultDto bulkImport(@RequestParam("file") MultipartFile file) {
        return ingredientService.bulkImport(file);
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

    @ExceptionHandler(BulkImportException.class)
    public ResponseEntity<Map<String, List<String>>> handleBulkImportError(BulkImportException ex) {
        return ResponseEntity.badRequest().body(Map.of("errors", ex.getErrors()));
    }
}
