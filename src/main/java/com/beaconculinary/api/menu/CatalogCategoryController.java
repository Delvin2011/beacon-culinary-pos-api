package com.beaconculinary.api.menu;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ADMIN (MENU_MANAGE until U2); GET also for the Head Chef (AdminSecurityRules). No delete. */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/catalog/categories")
public class CatalogCategoryController {
    private final CatalogCategoryService categoryService;

    @GetMapping
    public List<CatalogCategoryDto> getTree() {
        return categoryService.getTree();
    }

    @PostMapping
    public ResponseEntity<CatalogCategoryDto> create(@Valid @RequestBody CreateCatalogCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public CatalogCategoryDto update(@PathVariable Long id, @Valid @RequestBody UpdateCatalogCategoryRequest request) {
        return categoryService.update(id, request);
    }

    @ExceptionHandler(CatalogCategoryNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
