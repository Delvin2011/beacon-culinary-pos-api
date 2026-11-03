package com.beaconculinary.api.menu;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ADMIN; GET also for the Head Chef (AdminSecurityRules). No delete. */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/component-categories")
public class ComponentCategoryController {
    private final ComponentCategoryService categoryService;

    /** Active categories only by default; pass includeInactive=true for the management screen. */
    @GetMapping
    public List<ComponentCategoryDto> getAll(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return categoryService.getAll(includeInactive);
    }

    @PostMapping
    public ResponseEntity<ComponentCategoryDto> create(@Valid @RequestBody CreateComponentCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public ComponentCategoryDto update(@PathVariable Long id, @Valid @RequestBody UpdateComponentCategoryRequest request) {
        return categoryService.update(id, request);
    }

    @ExceptionHandler(ComponentCategoryNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
