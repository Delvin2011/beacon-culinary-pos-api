package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/daily-planning/{date}")
public class DailyPlanningIngredientController {
    private final DailyPlanningIngredientService dailyPlanningIngredientService;

    @GetMapping("/ingredient-requirements")
    public IngredientRequirementsResponseDto getRequirements(
            @PathVariable LocalDate date, @RequestParam String period) {
        return dailyPlanningIngredientService.getRequirements(date, period);
    }

    @PostMapping("/confirm-ingredient-requirements")
    public ConfirmIngredientRequirementsResponseDto confirm(
            @PathVariable LocalDate date, @Valid @RequestBody ConfirmIngredientRequirementsRequest request) {
        return dailyPlanningIngredientService.confirm(date, request);
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
