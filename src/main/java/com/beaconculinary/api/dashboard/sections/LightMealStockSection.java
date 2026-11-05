package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.inventory.LightMealStockService;
import com.beaconculinary.api.inventory.MealStockReviewDto;
import com.beaconculinary.api.menu.CatalogGroup;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.menu.StockStatus;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Light meals: the Kitchen stock review (LightMealStockService) for every active light meal in one
 * pass. Untracked meals are left out; OUT first, then LOW, then OK, at most 8.
 */
@Component
@AllArgsConstructor
public class LightMealStockSection implements SectionHandler {
    private static final int LIMIT = 8;
    private static final List<StockStatus> ORDER = List.of(StockStatus.OUT, StockStatus.LOW, StockStatus.OK);

    private final MealCatalogRepository mealCatalogRepository;
    private final LightMealStockService lightMealStockService;
    private final TradingCalendar calendar;

    @Override
    public DashboardSection section() {
        return DashboardSection.LIGHT_MEAL_STOCK;
    }

    @Override
    public Result compute(Request request) {
        var reviews = reviews();
        var meals = new ArrayList<Map<String, Object>>();
        for (var review : reviews.subList(0, Math.min(LIMIT, reviews.size()))) {
            var meal = new LinkedHashMap<String, Object>();
            meal.put("mealId", review.mealId());
            meal.put("name", review.name());
            meal.put("status", review.status());
            meal.put("unitsAvailable", review.unitsAvailable());
            meal.put("limitingIngredient", review.limitingIngredient());
            meal.put("lowStockUnits", review.lowStockUnits());
            meal.put("outToday", review.outToday());
            meals.add(meal);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("meals", meals);
        data.put("moreCount", Math.max(0, reviews.size() - LIMIT));
        return new Result(data, null);
    }

    /** Every tracked light meal, sorted OUT, LOW, OK, then by name; also read by Needs attention. */
    public List<MealStockReviewDto> reviews() {
        var lightMeals = mealCatalogRepository.findActiveAlwaysOn().stream()
                .filter(meal -> meal.getGroup() == CatalogGroup.LIGHT_MEALS)
                .toList();
        if (lightMeals.isEmpty()) {
            return List.of();
        }
        return lightMealStockService.reviewAll(lightMeals, calendar.today()).stream()
                .filter(MealStockReviewDto::tracked)
                .sorted(Comparator.comparingInt((MealStockReviewDto review) -> ORDER.indexOf(review.status()))
                        .thenComparing(MealStockReviewDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
