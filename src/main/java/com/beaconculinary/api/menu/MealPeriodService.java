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
        return mealPeriodRepository.findAll().stream().map(mealMapper::toDto).toList();
    }
}
