package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.Availability;
import com.beaconculinary.api.menu.CatalogGroup;
import com.beaconculinary.api.menu.MealCatalog;
import com.beaconculinary.api.menu.StockStatus;
import com.beaconculinary.api.orders.OrderLine;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Light meal stock. Light meals are never planned, so — unlike set meals, which deduct at planning
 * confirmation — their ingredients leave the Kitchen when they are sold: one CONSUMED movement per
 * ingredient per order line (source ORDER_LINE), read from the recipe at the time of sale. A void
 * gives them back once (CONSUMPTION_REVERSED); a refund doesn't, as the food was made.
 *
 * <p>Warn, never block: low or negative Kitchen stock never stops a sale, since the ledger can lag
 * what is physically there. A meal is tracked when it is a light meal (always available; beverages
 * stay untracked) and its components have at least one recipe line.
 */
@Service
@AllArgsConstructor
public class LightMealStockService {
    private static final String KITCHEN = "Kitchen";
    private static final String MAIN_STORE = "Main Store";
    private static final int LEDGER_SCALE = 4;

    private final MealIngredientRequirements mealIngredientRequirements;
    private final IngredientStockMovementRepository movementRepository;
    private final LocationRepository locationRepository;

    /** The stock figures a list or menu row shows. */
    public record MealStock(StockStatus status, Integer unitsAvailable) {
        static final MealStock NOT_TRACKED = new MealStock(StockStatus.NOT_TRACKED, null);
    }

    /** One review of a meal; its rows are in recipe order. */
    public MealStockReviewDto review(MealCatalog meal, LocalDate today) {
        var perUnit = trackedPerUnit(meal);
        if (perUnit.isEmpty()) {
            return new MealStockReviewDto(meal.getId(), meal.getName(), false, StockStatus.NOT_TRACKED, null, null,
                    meal.getLowStockUnits(), meal.isOutOn(today), List.of());
        }
        var ledger = ledger(perUnit);
        var kitchenId = kitchen().getId();
        var mainStoreId = mainStore().getId();

        var lines = new ArrayList<MealStockReviewDto.Line>();
        MealStockReviewDto.Line limiting = null;
        for (var row : perUnit) {
            var ingredient = row.ingredient();
            var kitchen = ledger.at(ingredient.getId(), kitchenId);
            var units = unitsPossible(kitchen, row.quantity());
            var line = new MealStockReviewDto.Line(ingredient.getId(), ingredient.getName(), ingredient.getUnit(),
                    row.quantity().setScale(LEDGER_SCALE, RoundingMode.HALF_UP), kitchen,
                    ledger.at(ingredient.getId(), mainStoreId), units, status(units, meal.getLowStockUnits()));
            lines.add(line);
            if (limiting == null || units < limiting.unitsPossible()) {
                limiting = line;
            }
        }
        return new MealStockReviewDto(meal.getId(), meal.getName(), true, limiting.status(), limiting.unitsPossible(),
                limiting.name(), meal.getLowStockUnits(), meal.isOutOn(today), lines);
    }

    /**
     * Stock for the meals list and till menu: one recipe query and one ledger query for every meal
     * given. Always-available meals get an entry (NOT_TRACKED for beverages and recipe-less light
     * meals); set meals get none.
     */
    public Map<Long, MealStock> stockFor(Collection<MealCatalog> meals) {
        var alwaysOn = meals.stream().filter(meal -> meal.getAvailability() == Availability.ALWAYS)
                .collect(Collectors.toMap(MealCatalog::getId, meal -> meal, (a, b) -> a));
        if (alwaysOn.isEmpty()) {
            return Map.of();
        }
        var lightMeals = alwaysOn.values().stream().filter(meal -> meal.getGroup() == CatalogGroup.LIGHT_MEALS).toList();
        var perUnitByMeal = lightMeals.isEmpty() ? Map.<Long, List<MealIngredientRequirements.PerUnit>>of()
                : mealIngredientRequirements.perUnitForMeals(lightMeals);
        var ledger = ledger(perUnitByMeal.values().stream().flatMap(List::stream).toList());
        var kitchenId = perUnitByMeal.values().stream().allMatch(List::isEmpty) ? null : kitchen().getId();

        Map<Long, MealStock> result = new HashMap<>();
        for (var meal : alwaysOn.values()) {
            var perUnit = perUnitByMeal.getOrDefault(meal.getId(), List.of());
            if (perUnit.isEmpty()) {
                result.put(meal.getId(), MealStock.NOT_TRACKED);
                continue;
            }
            var units = perUnit.stream()
                    .mapToInt(row -> unitsPossible(ledger.at(row.ingredient().getId(), kitchenId), row.quantity()))
                    .min().orElseThrow();
            result.put(meal.getId(), new MealStock(status(units, meal.getLowStockUnits()), units));
        }
        return result;
    }

    /**
     * Inside the order's transaction, once its lines have ids: a CONSUMED movement at the Kitchen
     * per ingredient of every tracked line, -(per unit x quantity). Planned (set meal) lines are
     * skipped: they deducted at planning confirmation. Complimentary orders deduct like sales.
     */
    public void deductForSale(Collection<OrderLine> lines, User recordedBy) {
        var lightMealLines = lines.stream()
                .filter(line -> line.getDailyMealOption().isAlwaysOn()
                        && line.getDailyMealOption().getMealCatalog().getGroup() == CatalogGroup.LIGHT_MEALS)
                .toList();
        if (lightMealLines.isEmpty()) {
            return;
        }
        var perUnitByMeal = mealIngredientRequirements.perUnitForMeals(lightMealLines.stream()
                .map(line -> line.getDailyMealOption().getMealCatalog()).distinct().toList());
        Location kitchen = null;
        var movements = new ArrayList<IngredientStockMovement>();
        for (var line : lightMealLines) {
            for (var row : perUnitByMeal.get(line.getDailyMealOption().getMealCatalog().getId())) {
                if (kitchen == null) {
                    kitchen = kitchen();
                }
                var quantity = row.quantity().multiply(BigDecimal.valueOf(line.getQuantity()))
                        .setScale(LEDGER_SCALE, RoundingMode.HALF_UP);
                movements.add(movement(row.ingredient(), MovementType.CONSUMED, quantity.negate(), kitchen,
                        line.getId(), recordedBy));
            }
        }
        movementRepository.saveAll(movements);
    }

    /**
     * Inside the void's transaction: gives back every CONSUMED movement these lines wrote, once.
     * Lines with nothing deducted (set meals, untracked items) have nothing to give back.
     */
    public void reverseForVoid(Collection<OrderLine> lines, User recordedBy) {
        var lineIds = lines.stream().map(OrderLine::getId).filter(Objects::nonNull).toList();
        if (lineIds.isEmpty()) {
            return;
        }
        var reversed = new HashSet<String>();
        movementRepository.findBySourceTypeAndSourceIdInAndMovementType(
                        MovementSourceType.ORDER_LINE, lineIds, MovementType.CONSUMPTION_REVERSED)
                .forEach(movement -> reversed.add(key(movement)));

        var reversals = movementRepository.findBySourceTypeAndSourceIdInAndMovementType(
                        MovementSourceType.ORDER_LINE, lineIds, MovementType.CONSUMED).stream()
                .filter(movement -> reversed.add(key(movement)))
                .map(movement -> movement(movement.getIngredient(), MovementType.CONSUMPTION_REVERSED,
                        movement.getQuantity().negate(), movement.getLocation(), movement.getSourceId(), recordedBy))
                .toList();
        movementRepository.saveAll(reversals);
    }

    /** Per-unit rows when the meal is tracked, else empty. */
    private List<MealIngredientRequirements.PerUnit> trackedPerUnit(MealCatalog meal) {
        if (meal.getGroup() != CatalogGroup.LIGHT_MEALS) {
            return List.of();
        }
        return mealIngredientRequirements.perUnit(meal);
    }

    /** floor(available / perUnit), never below 0. */
    private static int unitsPossible(BigDecimal available, BigDecimal perUnit) {
        if (perUnit.signum() <= 0) {
            return Integer.MAX_VALUE;
        }
        if (available.signum() <= 0) {
            return 0;
        }
        return available.divide(perUnit, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private static StockStatus status(int units, int lowStockUnits) {
        if (units == 0) {
            return StockStatus.OUT;
        }
        return units < lowStockUnits ? StockStatus.LOW : StockStatus.OK;
    }

    private Ledger ledger(Collection<MealIngredientRequirements.PerUnit> rows) {
        var ingredientIds = rows.stream().map(row -> row.ingredient().getId()).collect(Collectors.toSet());
        Map<String, BigDecimal> sums = new HashMap<>();
        if (!ingredientIds.isEmpty()) {
            for (var row : movementRepository.sumQuantityByIngredientIdsGroupedByLocation(ingredientIds)) {
                sums.put(row[0] + ":" + row[1], (BigDecimal) row[2]);
            }
        }
        return new Ledger(sums);
    }

    private record Ledger(Map<String, BigDecimal> sums) {
        BigDecimal at(Long ingredientId, Long locationId) {
            return sums.getOrDefault(ingredientId + ":" + locationId, BigDecimal.ZERO.setScale(LEDGER_SCALE));
        }
    }

    private static String key(IngredientStockMovement movement) {
        return movement.getSourceId() + ":" + movement.getIngredient().getId();
    }

    private static IngredientStockMovement movement(Ingredient ingredient, MovementType type, BigDecimal quantity,
                                                    Location location, Long orderLineId, User recordedBy) {
        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setLocation(location);
        movement.setSourceType(MovementSourceType.ORDER_LINE);
        movement.setSourceId(orderLineId);
        movement.setRecordedBy(recordedBy);
        return movement;
    }

    private Location kitchen() {
        return locationRepository.findByNameIgnoreCase(KITCHEN)
                .orElseThrow(() -> new IllegalStateException(KITCHEN + " location not seeded."));
    }

    private Location mainStore() {
        return locationRepository.findByNameIgnoreCase(MAIN_STORE)
                .orElseThrow(() -> new IllegalStateException(MAIN_STORE + " location not seeded."));
    }
}
