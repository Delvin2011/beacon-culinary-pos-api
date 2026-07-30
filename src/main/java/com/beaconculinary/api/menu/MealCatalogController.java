package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/meal-catalog")
public class MealCatalogController {
    private final MealCatalogService mealCatalogService;

    @GetMapping
    public List<MealCatalogDto> getAll() {
        return mealCatalogService.getAll();
    }

    @PostMapping
    public ResponseEntity<MealCatalogDto> create(@Valid @RequestBody CreateMealCatalogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mealCatalogService.create(request));
    }

    @PutMapping("/{id}")
    public MealCatalogDto update(@PathVariable Long id, @Valid @RequestBody UpdateMealCatalogRequest request) {
        return mealCatalogService.update(id, request);
    }

    @ExceptionHandler(MealCatalogNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidMenuRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidMenuRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
