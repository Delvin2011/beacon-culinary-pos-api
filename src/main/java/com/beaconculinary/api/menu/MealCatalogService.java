package com.beaconculinary.api.menu;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.inventory.LightMealStockService;
import com.beaconculinary.api.inventory.MealStockReviewDto;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class MealCatalogService {
    private final MealCatalogRepository mealCatalogRepository;
    private final ComponentCatalogRepository componentCatalogRepository;
    private final CatalogCategoryRepository categoryRepository;
    private final MealPeriodRepository mealPeriodRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final AlwaysOnMenuService alwaysOnMenuService;
    private final AuthService authService;
    private final LightMealStockService lightMealStockService;
    private final MealMapper mealMapper;
    private final Clock clock;

    /** Optional filters: group, categoryId, and periodId (the planning picker: only the meals
     * that period accepts and that can be planned, so none for All Day). */
    @Transactional(readOnly = true)
    public List<MealCatalogDto> getAll(CatalogGroup group, Long categoryId, Long periodId) {
        var period = periodId == null ? null : mealPeriodRepository.findById(periodId)
                .orElseThrow(() -> new InvalidMenuRequestException("periodId does not exist."));
        var meals = mealCatalogRepository.findAllWithComponentsBy().stream()
                .filter(meal -> group == null || meal.getGroup() == group)
                .filter(meal -> categoryId == null || meal.getCategory().getId().equals(categoryId))
                .filter(meal -> period == null || (meal.getGroup() == period.getAccepts()
                        && meal.getAvailability() == Availability.PLANNED))
                .toList();
        // One recipe query and one ledger query for every always-available row.
        var stock = lightMealStockService.stockFor(meals);
        return meals.stream().map(meal -> toDto(meal, stock)).toList();
    }

    /** What one unit of a light meal needs against the Kitchen's stock. A set meal deducts through
     * planning, so it has no review (400); a beverage is never tracked (NOT_TRACKED). */
    @Transactional(readOnly = true)
    public MealStockReviewDto stockReview(Long id) {
        var meal = mealCatalogRepository.findWithComponentsById(id).orElseThrow(MealCatalogNotFoundException::new);
        if (meal.getAvailability() != Availability.ALWAYS) {
            throw MenuRuleException.badRequest("STOCK_REVIEW_NOT_APPLICABLE",
                    "'" + meal.getName() + "' is a set meal: its ingredients come from the daily planning review.");
        }
        return lightMealStockService.review(meal, LocalDate.now(clock));
    }

    @Transactional
    public MealCatalogDto create(CreateMealCatalogRequest request) {
        var entity = new MealCatalog();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setPrice(request.getPrice());
        if (request.getLowStockUnits() != null) {
            entity.setLowStockUnits(request.getLowStockUnits());
        }
        entity.setCategory(resolveLeaf(request.getCategoryId()));
        entity.setStation(resolveStation(entity.getCategory(), request.getStation(), null));
        applyComponents(entity, request.getComponentIds());
        mealCatalogRepository.save(entity);
        return toDto(entity);
    }

    @Transactional
    public MealCatalogDto update(Long id, UpdateMealCatalogRequest request) {
        var entity = mealCatalogRepository.findWithComponentsById(id).orElseThrow(MealCatalogNotFoundException::new);
        var category = entity.getCategory().getId().equals(request.getCategoryId())
                ? entity.getCategory() : resolveLeaf(request.getCategoryId());
        if (category.getGroup() != entity.getGroup() && dailyMealOptionRepository
                .existsByMealCatalogIdAndOptionDateGreaterThanEqualAndPlannedPortionsIsNotNull(id, LocalDate.now(clock))) {
            throw MenuRuleException.conflict("MEAL_HAS_PLANNED_OPTIONS", "'" + entity.getName()
                    + "' is planned for today or later, so it can't move to another group. Remove those plans first.");
        }

        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setPrice(request.getPrice());
        if (request.getLowStockUnits() != null) {
            entity.setLowStockUnits(request.getLowStockUnits());
        }
        entity.setActive(request.isActive());
        entity.setStation(resolveStation(category, request.getStation(), entity.getStation()));
        entity.setCategory(category);
        if (entity.getAvailability() == Availability.PLANNED) {
            entity.setOutOn(null);
        }
        entity.getComponents().clear();
        applyComponents(entity, request.getComponentIds());
        mealCatalogRepository.save(entity);
        if (entity.getAvailability() == Availability.ALWAYS) {
            alwaysOnMenuService.syncToday(entity);
        }
        return toDto(entity);
    }

    /**
     * Set meals and light meals are prepared in the kitchen. A beverage is BAR or NONE: the one
     * asked for, else the one it has, else its category's default (Cold beverages: NONE; any other
     * beverage category: BAR).
     */
    private static Station resolveStation(CatalogCategory category, Station requested, Station current) {
        if (category.getGroup() != CatalogGroup.BEVERAGES) {
            if (requested != null && requested != Station.KITCHEN) {
                throw MenuRuleException.badRequest("STATION_INVALID", "Set meals and light meals are prepared in the kitchen.");
            }
            return Station.KITCHEN;
        }
        if (requested == Station.KITCHEN) {
            throw MenuRuleException.badRequest("STATION_INVALID",
                    "A beverage is prepared at the BAR, or needs no preparation (NONE).");
        }
        if (requested != null) {
            return requested;
        }
        if (current != null && current != Station.KITCHEN) {
            return current;
        }
        return "Cold beverages".equalsIgnoreCase(category.getName()) ? Station.NONE : Station.BAR;
    }

    /**
     * Marks an always-on item out for today, or back in. It clears itself the next day. ADMIN may
     * toggle any always-on item; the Head Chef light meals (DAILY_PLANNING until U2); the Barista
     * beverages (BAR_DISPLAY until U2).
     */
    @Transactional
    public MealCatalogDto setOutToday(Long id, boolean out) {
        var entity = mealCatalogRepository.findWithComponentsById(id).orElseThrow(MealCatalogNotFoundException::new);
        if (entity.getAvailability() != Availability.ALWAYS) {
            throw MenuRuleException.badRequest("OUT_TODAY_NOT_APPLICABLE",
                    "'" + entity.getName() + "' is a set meal: its availability comes from the daily plan.");
        }
        var role = authService.getCurrentUser().getRole();
        if (!mayToggleOutToday(role, entity.getGroup())) {
            throw new MenuRuleException(HttpStatus.FORBIDDEN, "OUT_TODAY_NOT_PERMITTED",
                    "You can't change whether '" + entity.getName() + "' is out today.");
        }
        entity.setOutOn(out ? LocalDate.now(clock) : null);
        mealCatalogRepository.save(entity);
        return toDto(entity);
    }

    private static boolean mayToggleOutToday(Role role, CatalogGroup group) {
        return role == Role.ADMIN
                || (role == Role.HEAD_CHEF && group == CatalogGroup.LIGHT_MEALS)
                || (role == Role.BARISTA && group == CatalogGroup.BEVERAGES);
    }

    /** A meal must sit in an active leaf: "Set meals" or a subcategory, never a group row. */
    private CatalogCategory resolveLeaf(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .filter(category -> category.isAssignable() && category.isActive())
                .orElseThrow(() -> MenuRuleException.badRequest("CATEGORY_INVALID",
                        "categoryId must be an active leaf category: Set meals, or a subcategory under Light meals or Beverages."));
    }

    private MealCatalogDto toDto(MealCatalog meal) {
        return toDto(meal, lightMealStockService.stockFor(List.of(meal)));
    }

    private MealCatalogDto toDto(MealCatalog meal, Map<Long, LightMealStockService.MealStock> stock) {
        var dto = mealMapper.toDto(meal);
        dto.setOutToday(meal.isOutOn(LocalDate.now(clock)));
        var mealStock = stock.get(meal.getId());
        if (mealStock != null) {
            dto.setStockStatus(mealStock.status());
            dto.setUnitsAvailable(mealStock.unitsAvailable());
        }
        return dto;
    }

    private void applyComponents(MealCatalog mealCatalog, List<Long> componentIds) {
        if (componentIds == null || componentIds.isEmpty()) return;

        var components = componentCatalogRepository.findAllByIdIn(componentIds);
        if (components.size() != new HashSet<>(componentIds).size()) {
            throw new InvalidMenuRequestException("One or more componentIds do not exist.");
        }

        for (var component : components) {
            var link = new MealCatalogComponent();
            link.setMealCatalog(mealCatalog);
            link.setComponentCatalog(component);
            mealCatalog.getComponents().add(link);
        }
    }
}
