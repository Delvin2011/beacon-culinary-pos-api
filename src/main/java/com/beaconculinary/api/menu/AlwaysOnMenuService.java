package com.beaconculinary.api.menu;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;

/**
 * Always-on items (light meals; beverages in Part B) are never planned: each active one gets one
 * daily option per day, created on demand with NULL (unlimited) portions, already READY, its
 * name/description/price snapshotted, under the period that accepts light meals (All Day).
 * Orders keep their own line price, so a later price change only moves today's option.
 */
@Service
public class AlwaysOnMenuService {
    private final MealCatalogRepository mealCatalogRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final MealPeriodRepository mealPeriodRepository;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public AlwaysOnMenuService(MealCatalogRepository mealCatalogRepository, DailyMealOptionRepository dailyMealOptionRepository,
                               MealPeriodRepository mealPeriodRepository, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.mealCatalogRepository = mealCatalogRepository;
        this.dailyMealOptionRepository = dailyMealOptionRepository;
        this.mealPeriodRepository = mealPeriodRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Makes sure every active always-on meal has its option on the date; returns how many were
     * created. Idempotent, and runs in its own transaction so the caller's (an order) is never
     * rolled back by it: if another request creates the same row first, the unique index
     * (UQ_daily_meal_options_always_on) refuses ours and a second pass finds it in place.
     */
    public int ensureAlwaysOnOptions(LocalDate date) {
        try {
            return newTransaction.execute(status -> createMissing(date));
        } catch (DataIntegrityViolationException e) {
            return newTransaction.execute(status -> createMissing(date));
        }
    }

    public int ensureAlwaysOnOptionsToday() {
        return ensureAlwaysOnOptions(LocalDate.now(clock));
    }

    private int createMissing(LocalDate date) {
        var existing = new HashSet<>(dailyMealOptionRepository.findAlwaysOnMealIds(date));
        var missing = mealCatalogRepository.findActiveAlwaysOn().stream()
                .filter(meal -> !existing.contains(meal.getId()))
                .toList();
        if (missing.isEmpty()) {
            return 0;
        }
        var lightMealsPeriod = mealPeriodRepository.findFirstByAcceptsOrderById(CatalogGroup.LIGHT_MEALS).orElse(null);
        var created = 0;
        for (var meal : missing) {
            var period = periodFor(meal.getGroup(), lightMealsPeriod);
            if (meal.getGroup() == CatalogGroup.LIGHT_MEALS && period == null) {
                continue;
            }
            var option = new DailyMealOption();
            option.setMealCatalog(meal);
            option.setMealPeriod(period);
            option.setOptionDate(date);
            option.setName(meal.getName());
            option.setDescription(meal.getDescription());
            option.setPrice(meal.getPrice());
            option.setPlannedPortions(null);
            option.setActualPortions(null);
            option.setPortionsRemaining(null);
            option.setStatus(DailyPlanItemStatus.READY);
            dailyMealOptionRepository.save(option);
            created++;
        }
        dailyMealOptionRepository.flush();
        return created;
    }

    /** Light meals sit under the light-meals period (All Day); beverages have none. */
    private static MealPeriod periodFor(CatalogGroup group, MealPeriod lightMealsPeriod) {
        return group == CatalogGroup.LIGHT_MEALS ? lightMealsPeriod : null;
    }

    /** After an always-on meal is edited: today's option follows its name, description and price.
     * Orders already placed keep their snapshotted line price. */
    @Transactional
    public void syncToday(MealCatalog meal) {
        dailyMealOptionRepository.findByMealCatalogIdAndOptionDateAndPlannedPortionsIsNull(meal.getId(), LocalDate.now(clock))
                .ifPresent(option -> {
                    option.setName(meal.getName());
                    option.setDescription(meal.getDescription());
                    option.setPrice(meal.getPrice());
                    dailyMealOptionRepository.save(option);
                });
    }

    /** Whether an always-on option still belongs on the menu: its meal is active and still in an
     * always-available group. A meal marked out today stays listed (outToday), but can't be sold. */
    public static boolean listed(DailyMealOption option) {
        if (!option.isAlwaysOn()) {
            return true;
        }
        var meal = option.getMealCatalog();
        return meal.isActive() && meal.getAvailability() == Availability.ALWAYS;
    }
}
