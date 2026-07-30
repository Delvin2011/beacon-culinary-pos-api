package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/meal-periods")
public class MealPeriodController {
    private final MealPeriodService mealPeriodService;

    @GetMapping
    public List<MealPeriodDto> getAll() {
        return mealPeriodService.getAll();
    }
}
