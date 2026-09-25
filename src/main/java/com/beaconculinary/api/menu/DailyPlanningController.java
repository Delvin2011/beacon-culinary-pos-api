package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** All ADMIN-only via AdminSecurityRules' /admin/** fallback. */
@AllArgsConstructor
@RestController
public class DailyPlanningController {
    private final DailyPlanningService dailyPlanningService;

    @PostMapping("/admin/daily-options")
    public ResponseEntity<DailyMealOptionDto> createDailyOption(@Valid @RequestBody CreateDailyOptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyPlanningService.createDailyOption(request));
    }

    @PostMapping("/admin/daily-component-stock")
    public ResponseEntity<DailyComponentStockDto> createDailyComponentStock(@Valid @RequestBody CreateDailyComponentStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyPlanningService.createDailyComponentStock(request));
    }

    @PutMapping("/admin/daily-options/{id}/actual")
    public DailyPlanOptionDto recordActualPortions(
            @PathVariable Long id, @Valid @RequestBody RecordActualPortionsRequest request) {
        return dailyPlanningService.recordActualPortions(id, request);
    }

    @PutMapping("/admin/daily-component-stock/{id}/actual")
    public DailyPlanComponentStockDto recordActualQuantity(
            @PathVariable Long id, @Valid @RequestBody RecordActualQuantityRequest request) {
        return dailyPlanningService.recordActualQuantity(id, request);
    }

    /** The planning page's view — every item regardless of status, unlike the POS's /menu/today. */
    @GetMapping("/admin/daily-planning/{date}")
    public DailyPlanResponseDto getDailyPlan(@PathVariable LocalDate date, @RequestParam String period) {
        return dailyPlanningService.getDailyPlan(date, period);
    }

    @ExceptionHandler(DailyPlanItemNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidMenuRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidMenuRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
