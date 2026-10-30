package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.ComponentCatalog;
import com.beaconculinary.api.menu.DailyComponentStock;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOption;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.DailyPlanItemStatus;
import com.beaconculinary.api.menu.MealPeriod;
import com.beaconculinary.api.menu.MealPeriodRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 Part C — the consolidated, editable review step at daily-planning time.
 *
 * <p>Stage 5 Revision — confirming no longer deducts stock directly. It creates a {@link
 * StockRequest} ({@code requestType = ISSUE}, {@code source = DAILY_PLANNING}) awaiting a {@code
 * STOCK_ADMIN}'s authorization via {@code POST /stock-requests/{id}/action} — stock only actually
 * moves once that Issuing Sheet is approved. The shortfall list this step still returns is a
 * preview only (informational, against Main Store's current stock — the same figure the eventual
 * approval will check the cap against), not a block.
 */
@Service
@AllArgsConstructor
public class DailyPlanningIngredientService {
    private static final String ISSUE_SOURCE_LOCATION = "Main Store";
    private static final String ISSUE_DESTINATION_LOCATION = "Kitchen";

    private final MealPeriodRepository mealPeriodRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final MealIngredientRequirements mealIngredientRequirements;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final StockRequestRepository stockRequestRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public IngredientRequirementsResponseDto getRequirements(LocalDate date, String period) {
        var mealPeriod = resolvePeriod(period);
        var calculation = calculate(date, mealPeriod.getId());
        var mainStoreId = mainStore().getId();
        var kitchenId = kitchen().getId();

        var requirements = calculation.byIngredientId().entrySet().stream()
                .map(entry -> toRequirementDto(entry.getKey(), entry.getValue(), mainStoreId, kitchenId))
                .toList();
        return new IngredientRequirementsResponseDto(requirements, calculation.uncovered());
    }

    @Transactional
    public ConfirmIngredientRequirementsResponseDto confirm(LocalDate date, ConfirmIngredientRequirementsRequest request) {
        var mealPeriod = resolvePeriod(request.getPeriod());
        // Re-fetch the same unreviewed set the GET would have returned for this date/period —
        // never trust a stale client-side snapshot.
        var calculation = calculate(date, mealPeriod.getId());
        var mainStore = mainStore();

        var stockRequest = new StockRequest();
        stockRequest.setRequestType(StockRequestType.ISSUE);
        stockRequest.setSource(StockRequestSource.DAILY_PLANNING);
        stockRequest.setRequestedBy(authService.getCurrentUser());
        stockRequest.setStatus(StockRequestStatus.REQUESTED);
        stockRequest.setDailyPlanDate(date);
        stockRequest.setDailyPlanPeriod(mealPeriod);

        var shortfalls = new ArrayList<IngredientShortfallDto>();
        for (var adjustment : request.getAdjustments()) {
            var ingredient = ingredientRepository.findById(adjustment.getIngredientId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

            // Preview only — informational, since the real cap-and-authorize decision now
            // happens later, at POST /stock-requests/{id}/action, not here.
            var currentStock = ingredientStockMovementRepository
                    .sumQuantityByIngredientIdAndLocationId(ingredient.getId(), mainStore.getId());
            var resultingStock = currentStock.subtract(adjustment.getFinalQuantity());
            if (resultingStock.compareTo(BigDecimal.ZERO) < 0) {
                shortfalls.add(new IngredientShortfallDto(
                        ingredient.getId(), ingredient.getName(), ingredient.getUnit(),
                        adjustment.getFinalQuantity(), resultingStock));
            }

            var line = new StockRequestLine();
            line.setStockRequest(stockRequest);
            line.setIngredient(ingredient);
            line.setRequestedQuantity(adjustment.getFinalQuantity());
            stockRequest.getLines().add(line);
        }

        stockRequestRepository.save(stockRequest);

        // Every row that fed this calculation is now locked — never revisited by this stage.
        // Reality diverging from the plan is corrected via Waste/Stock Take entries instead. The
        // *review* is complete here even though the *deduction* is deferred to whoever
        // authorizes the resulting Issuing Sheet.
        calculation.options().forEach(option -> option.setStatus(DailyPlanItemStatus.INGREDIENTS_REVIEWED));
        dailyMealOptionRepository.saveAll(calculation.options());
        calculation.stocks().forEach(stock -> stock.setStatus(DailyPlanItemStatus.INGREDIENTS_REVIEWED));
        dailyComponentStockRepository.saveAll(calculation.stocks());

        return new ConfirmIngredientRequirementsResponseDto(shortfalls, stockRequest.getId());
    }

    private IngredientRequirementDto toRequirementDto(
            Long ingredientId, BigDecimal calculatedQuantity, Long mainStoreId, Long kitchenId) {
        var ingredient = ingredientRepository.findById(ingredientId).orElseThrow(IngredientNotFoundException::new);
        var currentStock = ingredientStockMovementRepository.sumQuantityByIngredientIdAndLocationId(ingredientId, mainStoreId);
        var kitchenStock = ingredientStockMovementRepository.sumQuantityByIngredientIdAndLocationId(ingredientId, kitchenId);
        return new IngredientRequirementDto(
                ingredient.getId(), ingredient.getName(), ingredient.getUnit(), calculatedQuantity, currentStock, kitchenStock);
    }

    private MealPeriod resolvePeriod(String period) {
        return mealPeriodRepository.findByNameIgnoreCase(period)
                .orElseThrow(() -> new InvalidInventoryRequestException("Unknown meal period: " + period));
    }

    private Location mainStore() {
        return locationRepository.findByNameIgnoreCase(ISSUE_SOURCE_LOCATION)
                .orElseThrow(() -> new IllegalStateException(ISSUE_SOURCE_LOCATION + " location not seeded."));
    }

    private Location kitchen() {
        return locationRepository.findByNameIgnoreCase(ISSUE_DESTINATION_LOCATION)
                .orElseThrow(() -> new IllegalStateException(ISSUE_DESTINATION_LOCATION + " location not seeded."));
    }

    // calculatedQuantity per ingredient = SUM over every unreviewed DailyMealOption of [SUM over
    // its components' recipe lines of (recipeLine.quantity / recipe.batchSize) x
    // dailyMealOption.plannedPortions] plus SUM over every unreviewed DailyComponentStock of
    // [SUM over its own component's recipe lines of (recipeLine.quantity / recipe.batchSize) x
    // dailyComponentStock.bufferQuantity].
    private RequirementsCalculation calculate(LocalDate date, Long mealPeriodId) {
        // Always-on options (NULL portions) are never planned and are created READY; the filter
        // keeps them out even so.
        var options = dailyMealOptionRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                date, mealPeriodId, DailyPlanItemStatus.PLANNED).stream()
                .filter(option -> !option.isAlwaysOn())
                .toList();
        var stocks = dailyComponentStockRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                date, mealPeriodId, DailyPlanItemStatus.PLANNED);

        Map<Long, BigDecimal> byIngredientId = new LinkedHashMap<>();
        var uncovered = new ArrayList<UncoveredPlanItemDto>();

        for (var option : options) {
            var links = option.getMealCatalog().getComponents();
            if (links.isEmpty()) {
                uncovered.add(new UncoveredPlanItemDto(UncoveredPlanItemDto.Type.MEAL, option.getId(), option.getName(),
                        UncoveredPlanItemDto.Reason.NO_COMPONENTS, List.of()));
                continue;
            }
            var withoutRecipe = new ArrayList<String>();
            for (var link : links) {
                if (!accumulate(byIngredientId, link.getComponentCatalog(), option.getPlannedPortions())) {
                    withoutRecipe.add(link.getComponentCatalog().getName());
                }
            }
            if (!withoutRecipe.isEmpty()) {
                uncovered.add(new UncoveredPlanItemDto(UncoveredPlanItemDto.Type.MEAL, option.getId(), option.getName(),
                        UncoveredPlanItemDto.Reason.NO_RECIPE, withoutRecipe));
            }
        }
        for (var stock : stocks) {
            if (!accumulate(byIngredientId, stock.getComponentCatalog(), stock.getBufferQuantity())) {
                var name = stock.getComponentCatalog().getName();
                uncovered.add(new UncoveredPlanItemDto(UncoveredPlanItemDto.Type.EXTRA, stock.getId(), name,
                        UncoveredPlanItemDto.Reason.NO_RECIPE, List.of(name)));
            }
        }

        return new RequirementsCalculation(byIngredientId, options, stocks, uncovered);
    }

    /**
     * Adds a component's recipe, scaled to the portions, into the running totals. Returns false
     * when it contributes nothing (no recipe, or a recipe with no lines), so the caller can
     * report it as uncovered. Such a component is still allowed — not every extra/dish has raw
     * ingredients worth tracking (e.g. a bought-in, pre-packaged item).
     */
    private boolean accumulate(Map<Long, BigDecimal> byIngredientId, ComponentCatalog componentCatalog, Integer portions) {
        var perUnit = mealIngredientRequirements.perUnitOfComponent(componentCatalog.getId());
        for (var row : perUnit) {
            var required = row.quantity().multiply(BigDecimal.valueOf(portions));
            byIngredientId.merge(row.ingredient().getId(), required, BigDecimal::add);
        }
        return !perUnit.isEmpty();
    }

    private record RequirementsCalculation(Map<Long, BigDecimal> byIngredientId, List<DailyMealOption> options,
                                           List<DailyComponentStock> stocks, List<UncoveredPlanItemDto> uncovered) {
    }
}
