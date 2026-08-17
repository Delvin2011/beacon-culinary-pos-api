package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Consolidates creating/updating a component (name + per-portion price) and replacing its
 * recipe line items — previously POST /admin/component-catalog followed by PUT
 * /admin/components/{id}/recipe — into a single CSV upload. */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/component-catalog")
public class ComponentRecipeImportController {
    private final ComponentRecipeImportService componentRecipeImportService;

    /** CSV upload with COMPONENT, INGREDIENT (NAME), UNIT, COUNT SHEET, QUANTITIES, BATCH SIZE,
     * and PER PORTION PRICE columns (header names matched case/whitespace-insensitively) — one
     * row per recipe line, with the owning component's own columns (batch size, price) repeated
     * on every one of its rows. Components and ingredients are upserted by name; each
     * component's recipe is replaced wholesale from its rows. */
    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkComponentImportResultDto bulkImport(@RequestParam("file") MultipartFile file) {
        return componentRecipeImportService.bulkImport(file);
    }

    @ExceptionHandler(BulkImportException.class)
    public ResponseEntity<Map<String, List<String>>> handleBulkImportError(BulkImportException ex) {
        return ResponseEntity.badRequest().body(Map.of("errors", ex.getErrors()));
    }
}
