package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.ComponentCatalog;
import com.beaconculinary.api.menu.DailyComponentStock;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOption;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealPeriod;
import com.beaconculinary.api.menu.MealPeriodRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 Part C — the consolidated, editable review step at daily-planning time. Ingredient
 * deduction never happens per sale (nothing here touches /orders or any cashier/kitchen/public
 * endpoint) — it happens once, in a batch, when the chef confirms a date + meal period's
 * requirements. Insufficient stock warns (via the returned shortfalls list), never blocks.
 */
@Service
@AllArgsConstructor
public class DailyPlanningIngredientService {
    private final MealPeriodRepository mealPeriodRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final IngredientRequirementConfirmationRepository confirmationRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public IngredientRequirementsResponseDto getRequirements(LocalDate date, String period) {
        var mealPeriod = resolvePeriod(period);
        var calculation = calculate(date, mealPeriod.getId());

        var requirements = calculation.byIngredientId().entrySet().stream()
                .map(entry -> toRequirementDto(entry.getKey(), entry.getValue()))
                .toList();
        return new IngredientRequirementsResponseDto(requirements);
    }

    @Transactional
    public ConfirmIngredientRequirementsResponseDto confirm(LocalDate date, ConfirmIngredientRequirementsRequest request) {
        var mealPeriod = resolvePeriod(request.getPeriod());
        // Re-fetch the same unreviewed set the GET would have returned for this date/period —
        // never trust a stale client-side snapshot.
        var calculation = calculate(date, mealPeriod.getId());

        var confirmation = new IngredientRequirementConfirmation();
        confirmation.setPlanningDate(date);
        confirmation.setMealPeriod(mealPeriod);
        confirmation.setConfirmedBy(authService.getCurrentUser());
        confirmationRepository.save(confirmation);

        var shortfalls = new ArrayList<IngredientShortfallDto>();
        for (var adjustment : request.getAdjustments()) {
            var ingredient = ingredientRepository.findById(adjustment.getIngredientId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

            var currentStock = ingredientStockMovementRepository.sumQuantityByIngredientId(ingredient.getId());
            var resultingStock = currentStock.subtract(adjustment.getFinalQuantity());
            if (resultingStock.compareTo(BigDecimal.ZERO) < 0) {
                shortfalls.add(new IngredientShortfallDto(
                        ingredient.getId(), ingredient.getName(), ingredient.getUnit(),
                        adjustment.getFinalQuantity(), resultingStock));
            }

            var movement = new IngredientStockMovement();
            movement.setIngredient(ingredient);
            movement.setMovementType(MovementType.CONSUMED_FOR_PREP);
            // Negative — stock going out. Allowed to go below zero, per the locked decision:
            // this stage warns, it never blocks.
            movement.setQuantity(adjustment.getFinalQuantity().negate());
            movement.setSourceType(MovementSourceType.DAILY_PLANNING_CONFIRMATION);
            movement.setSourceId(confirmation.getId());
            movement.setRecordedBy(confirmation.getConfirmedBy());
            ingredientStockMovementRepository.save(movement);
        }

        // Every row that fed this calculation is now locked — never revisited by this stage.
        // Reality diverging from the plan is corrected via Waste/Stock Take entries instead.
        calculation.options().forEach(option -> option.setIngredientsReviewed(true));
        dailyMealOptionRepository.saveAll(calculation.options());
        calculation.stocks().forEach(stock -> stock.setIngredientsReviewed(true));
        dailyComponentStockRepository.saveAll(calculation.stocks());

        return new ConfirmIngredientRequirementsResponseDto(shortfalls);
    }

    private IngredientRequirementDto toRequirementDto(Long ingredientId, BigDecimal calculatedQuantity) {
        var ingredient = ingredientRepository.findById(ingredientId).orElseThrow(IngredientNotFoundException::new);
        var currentStock = ingredientStockMovementRepository.sumQuantityByIngredientId(ingredientId);
        return new IngredientRequirementDto(ingredient.getId(), ingredient.getName(), ingredient.getUnit(), calculatedQuantity, currentStock);
    }

    private MealPeriod resolvePeriod(String period) {
        return mealPeriodRepository.findByNameIgnoreCase(period)
                .orElseThrow(() -> new InvalidInventoryRequestException("Unknown meal period: " + period));
    }

    // calculatedQuantity per ingredient = SUM over every unreviewed DailyMealOption of [SUM over
    // its components' recipe lines of (recipeLine.quantity / recipe.batchSize) x
    // dailyMealOption.plannedPortions] plus SUM over every unreviewed DailyComponentStock of
    // [SUM over its own component's recipe lines of (recipeLine.quantity / recipe.batchSize) x
    // dailyComponentStock.bufferQuantity].
    private RequirementsCalculation calculate(LocalDate date, Long mealPeriodId) {
        var options = dailyMealOptionRepository.findByOptionDateAndMealPeriodIdAndIngredientsReviewedFalse(date, mealPeriodId);
        var stocks = dailyComponentStockRepository.findByOptionDateAndMealPeriodIdAndIngredientsReviewedFalse(date, mealPeriodId);

        Map<Long, BigDecimal> byIngredientId = new LinkedHashMap<>();

        for (var option : options) {
            for (var link : option.getMealCatalog().getComponents()) {
                accumulate(byIngredientId, link.getComponentCatalog(), option.getPlannedPortions());
            }
        }
        for (var stock : stocks) {
            accumulate(byIngredientId, stock.getComponentCatalog(), stock.getBufferQuantity());
        }

        return new RequirementsCalculation(byIngredientId, options, stocks);
    }

    private void accumulate(Map<Long, BigDecimal> byIngredientId, ComponentCatalog componentCatalog, Integer portions) {
        // A component without a recipe is simply excluded — not every extra/dish has raw
        // ingredients worth tracking (e.g. a bought-in, pre-packaged item).
        recipeRepository.findWithLinesByComponentCatalogId(componentCatalog.getId()).ifPresent(recipe -> {
            var batchSize = BigDecimal.valueOf(recipe.getBatchSize());
            for (var line : recipe.getLines()) {
                var perPortion = line.getQuantity().divide(batchSize, 6, RoundingMode.HALF_UP);
                var required = perPortion.multiply(BigDecimal.valueOf(portions));
                byIngredientId.merge(line.getIngredient().getId(), required, BigDecimal::add);
            }
        });
    }

    private record RequirementsCalculation(
            Map<Long, BigDecimal> byIngredientId, List<DailyMealOption> options, List<DailyComponentStock> stocks) {
    }
}
