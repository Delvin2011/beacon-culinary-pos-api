package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @ExceptionHandler(InvalidMenuRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidMenuRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
