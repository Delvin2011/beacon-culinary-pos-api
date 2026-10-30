package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.MealCatalog;
import com.beaconculinary.api.menu.MealCatalogComponent;
import com.beaconculinary.api.menu.MealCatalogNotFoundException;
import com.beaconculinary.api.menu.MealCatalogRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place a recipe becomes ingredients per portion, shared by the daily-planning review and
 * light meal stock. For one portion of a meal:
 * {@code per-unit quantity = SUM over its components of (recipeLine.quantity / recipe.batchSize)}.
 * A meal lists each component once, so a component's quantity in the meal is always 1. Each
 * recipe line is divided at 6 decimals, as planning always has; callers multiply by portions.
 */
@Component
@AllArgsConstructor
public class MealIngredientRequirements {
    private static final int PER_UNIT_SCALE = 6;

    private final RecipeRepository recipeRepository;
    private final MealCatalogRepository mealCatalogRepository;

    /** One ingredient's quantity for one portion. */
    public record PerUnit(Ingredient ingredient, BigDecimal quantity) {
    }

    /** One portion of a meal, one row per ingredient; empty when no component has recipe lines. */
    public List<PerUnit> perUnit(Long mealId) {
        var meal = mealCatalogRepository.findWithComponentsById(mealId).orElseThrow(MealCatalogNotFoundException::new);
        return perUnit(meal);
    }

    public List<PerUnit> perUnit(MealCatalog meal) {
        return perUnitForMeals(List.of(meal)).get(meal.getId());
    }

    /** Many meals at once, with one recipe query: meal id -> its per-unit rows. */
    public Map<Long, List<PerUnit>> perUnitForMeals(Collection<MealCatalog> meals) {
        var componentIds = meals.stream()
                .flatMap(meal -> meal.getComponents().stream())
                .map(link -> link.getComponentCatalog().getId())
                .collect(Collectors.toSet());
        Map<Long, Recipe> recipesByComponentId = componentIds.isEmpty() ? Map.of()
                : recipeRepository.findWithLinesByComponentCatalogIdIn(componentIds).stream()
                        .collect(Collectors.toMap(recipe -> recipe.getComponentCatalog().getId(), Function.identity()));

        Map<Long, List<PerUnit>> result = new HashMap<>();
        for (var meal : meals) {
            Map<Long, PerUnit> byIngredientId = new LinkedHashMap<>();
            for (MealCatalogComponent link : meal.getComponents()) {
                var recipe = recipesByComponentId.get(link.getComponentCatalog().getId());
                if (recipe != null) {
                    perUnitOf(recipe).forEach(row -> byIngredientId.merge(row.ingredient().getId(), row,
                            (a, b) -> new PerUnit(a.ingredient(), a.quantity().add(b.quantity()))));
                }
            }
            result.put(meal.getId(), List.copyOf(byIngredientId.values()));
        }
        return result;
    }

    /** One portion of a component, from its recipe; empty when it has no recipe or no lines. */
    public List<PerUnit> perUnitOfComponent(Long componentCatalogId) {
        return recipeRepository.findWithLinesByComponentCatalogId(componentCatalogId)
                .map(MealIngredientRequirements::perUnitOf)
                .orElse(List.of());
    }

    private static List<PerUnit> perUnitOf(Recipe recipe) {
        var batchSize = BigDecimal.valueOf(recipe.getBatchSize());
        Map<Long, PerUnit> byIngredientId = new LinkedHashMap<>();
        for (var line : recipe.getLines()) {
            var perPortion = line.getQuantity().divide(batchSize, PER_UNIT_SCALE, RoundingMode.HALF_UP);
            byIngredientId.merge(line.getIngredient().getId(), new PerUnit(line.getIngredient(), perPortion),
                    (a, b) -> new PerUnit(a.ingredient(), a.quantity().add(b.quantity())));
        }
        return List.copyOf(byIngredientId.values());
    }
}
