package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.inventory.MealStockReviewDto;
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
    public List<MealCatalogDto> getAll(@RequestParam(required = false) CatalogGroup group,
                                       @RequestParam(required = false) Long categoryId,
                                       @RequestParam(required = false) Long periodId) {
        return mealCatalogService.getAll(group, categoryId, periodId);
    }

    @PostMapping
    public ResponseEntity<MealCatalogDto> create(@Valid @RequestBody CreateMealCatalogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mealCatalogService.create(request));
    }

    @PutMapping("/{id}")
    public MealCatalogDto update(@PathVariable Long id, @Valid @RequestBody UpdateMealCatalogRequest request) {
        return mealCatalogService.update(id, request);
    }

    /** ADMIN, the Head Chef (light meals); see MealCatalogService.setOutToday. */
    @PostMapping("/{id}/out-today")
    public MealCatalogDto setOutToday(@PathVariable Long id, @Valid @RequestBody OutTodayRequest request) {
        return mealCatalogService.setOutToday(id, request.getOut());
    }

    /** Light meal stock review: ADMIN, the Head Chef (MENU_VIEW and STOCK_VIEW until U2). */
    @GetMapping("/{id}/stock-review")
    public MealStockReviewDto stockReview(@PathVariable Long id) {
        return mealCatalogService.stockReview(id);
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
