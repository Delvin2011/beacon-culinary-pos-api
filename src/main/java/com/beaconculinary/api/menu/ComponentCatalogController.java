package com.beaconculinary.api.menu;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/component-catalog")
public class ComponentCatalogController {
    private final ComponentCatalogService componentCatalogService;

    /** ?categoryId= one category · ?uncategorised=true components without one · ?name= contains,
     * case-insensitive. Filters combine; with none, every component. */
    @GetMapping
    public List<ComponentCatalogDto> search(@RequestParam(required = false) Long categoryId,
                                            @RequestParam(defaultValue = "false") boolean uncategorised,
                                            @RequestParam(required = false) String name) {
        return componentCatalogService.search(categoryId, uncategorised, name);
    }

    @PostMapping
    public ResponseEntity<ComponentCatalogDto> create(@Valid @RequestBody CreateComponentCatalogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(componentCatalogService.create(request));
    }

    @PutMapping("/{id}")
    public ComponentCatalogDto update(@PathVariable Long id, @Valid @RequestBody UpdateComponentCatalogRequest request) {
        return componentCatalogService.update(id, request);
    }

    @ExceptionHandler(ComponentCatalogNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
