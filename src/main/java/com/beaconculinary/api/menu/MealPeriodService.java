package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class MealPeriodService {
    private final MealPeriodRepository mealPeriodRepository;
    private final MealMapper mealMapper;

    public List<MealPeriodDto> getAll() {
        // T0-5a: active periods only, in sort order. The DTO (the public shape) is unchanged.
        return mealPeriodRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(mealMapper::toDto).toList();
    }
}
