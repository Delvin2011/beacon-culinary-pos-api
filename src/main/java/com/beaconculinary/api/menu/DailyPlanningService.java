package com.beaconculinary.api.menu;

import com.beaconculinary.api.inventory.LightMealStockService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Service
@AllArgsConstructor
public class DailyPlanningService {
    private final MealPeriodRepository mealPeriodRepository;
    private final MealCatalogRepository mealCatalogRepository;
    private final ComponentCatalogRepository componentCatalogRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final LightMealStockService lightMealStockService;
    private final MealMapper mealMapper;
    private final Clock clock;

    private static final Comparator<DailyMealOption> TILL_ORDER = Comparator
            .comparing((DailyMealOption option) -> option.getMealCatalog().getGroup())
            .thenComparingInt(option -> option.getMealCatalog().getCategory().getSortOrder())
            .thenComparing(option -> option.getMealCatalog().getCategory().getName())
            .thenComparing(DailyMealOption::getName, String.CASE_INSENSITIVE_ORDER);

    @Transactional
    public DailyMealOptionDto createDailyOption(CreateDailyOptionRequest request) {
        var mealPeriod = mealPeriodRepository.findById(request.getMealPeriodId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealPeriodId does not exist."));
        var mealCatalog = mealCatalogRepository.findById(request.getMealCatalogId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealCatalogId does not exist."));
        requirePlannableIn(mealCatalog, mealPeriod);

        var option = new DailyMealOption();
        option.setMealPeriod(mealPeriod);
        option.setMealCatalog(mealCatalog);
        option.setOptionDate(request.getOptionDate());
        // Snapshot from the catalog — the chef never enters a price, and a later catalog
        // change must never retroactively rewrite an already-planned day.
        option.setName(mealCatalog.getName());
        option.setDescription(mealCatalog.getDescription());
        option.setPrice(mealCatalog.getPrice());
        option.setPlannedPortions(request.getPlannedPortions());
        // Nothing to sell until an actual is recorded — see recordActualPortions.
        option.setPortionsRemaining(0);
        option.setStatus(DailyPlanItemStatus.PLANNED);

        dailyMealOptionRepository.save(option);
        return mealMapper.toDto(option);
    }

    @Transactional
    public DailyComponentStockDto createDailyComponentStock(CreateDailyComponentStockRequest request) {
        var componentCatalog = componentCatalogRepository.findById(request.getComponentCatalogId())
                .orElseThrow(() -> new InvalidMenuRequestException("componentCatalogId does not exist."));
        var mealPeriod = mealPeriodRepository.findById(request.getMealPeriodId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealPeriodId does not exist."));

        var stock = new DailyComponentStock();
        stock.setComponentCatalog(componentCatalog);
        stock.setMealPeriod(mealPeriod);
        stock.setOptionDate(request.getOptionDate());
        stock.setExtraPrice(componentCatalog.getExtraPrice());
        stock.setBufferQuantity(request.getBufferQuantity());
        // Nothing to sell until an actual is recorded — see recordActualQuantity.
        stock.setBufferRemaining(0);
        stock.setStatus(DailyPlanItemStatus.PLANNED);

        dailyComponentStockRepository.save(stock);
        return mealMapper.toDto(stock);
    }

    /**
     * Corrects a planned meal: its meal, period, date or planned portions. Only while PLANNED —
     * once its ingredients are reviewed it feeds an Issuing Sheet, and only READY items can be
     * sold, so a PLANNED option has no orders or issues against it. Choosing a different meal
     * snapshots that meal's name, description and price; keeping the same meal keeps the
     * snapshot, so a later catalog price change still never rewrites a planned day.
     */
    @Transactional
    public DailyMealOptionDto updateDailyOption(Long id, CreateDailyOptionRequest request) {
        var option = dailyMealOptionRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        requirePlanned(option.getStatus(), option.getName());
        var mealPeriod = mealPeriodRepository.findById(request.getMealPeriodId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealPeriodId does not exist."));
        var mealChanged = !option.getMealCatalog().getId().equals(request.getMealCatalogId());
        var mealCatalog = mealChanged
                ? mealCatalogRepository.findById(request.getMealCatalogId())
                        .orElseThrow(() -> new InvalidMenuRequestException("mealCatalogId does not exist."))
                : option.getMealCatalog();
        // Only a change of meal or period is checked, so an older option keeps working as it was.
        if (mealChanged || !option.getMealPeriod().getId().equals(mealPeriod.getId())) {
            requirePlannableIn(mealCatalog, mealPeriod);
        }

        if (mealChanged) {
            option.setMealCatalog(mealCatalog);
            option.setName(mealCatalog.getName());
            option.setDescription(mealCatalog.getDescription());
            option.setPrice(mealCatalog.getPrice());
        }
        option.setMealPeriod(mealPeriod);
        option.setOptionDate(request.getOptionDate());
        option.setPlannedPortions(request.getPlannedPortions());

        dailyMealOptionRepository.save(option);
        return mealMapper.toDto(option);
    }

    /** Removes a planned meal from the day. Only while PLANNED (see {@link #updateDailyOption}). */
    @Transactional
    public void deleteDailyOption(Long id) {
        var option = dailyMealOptionRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        requirePlanned(option.getStatus(), option.getName());
        dailyMealOptionRepository.delete(option);
    }

    /** Same rules as {@link #updateDailyOption}, for a planned extra. A different component
     * snapshots its extra price; the same component keeps the planned one. */
    @Transactional
    public DailyComponentStockDto updateDailyComponentStock(Long id, CreateDailyComponentStockRequest request) {
        var stock = dailyComponentStockRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        requirePlanned(stock.getStatus(), stock.getComponentCatalog().getName());
        var mealPeriod = mealPeriodRepository.findById(request.getMealPeriodId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealPeriodId does not exist."));

        if (!stock.getComponentCatalog().getId().equals(request.getComponentCatalogId())) {
            var componentCatalog = componentCatalogRepository.findById(request.getComponentCatalogId())
                    .orElseThrow(() -> new InvalidMenuRequestException("componentCatalogId does not exist."));
            stock.setComponentCatalog(componentCatalog);
            stock.setExtraPrice(componentCatalog.getExtraPrice());
        }
        stock.setMealPeriod(mealPeriod);
        stock.setOptionDate(request.getOptionDate());
        stock.setBufferQuantity(request.getBufferQuantity());

        dailyComponentStockRepository.save(stock);
        return mealMapper.toDto(stock);
    }

    /** Removes a planned extra from the day. Only while PLANNED. */
    @Transactional
    public void deleteDailyComponentStock(Long id) {
        var stock = dailyComponentStockRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        requirePlanned(stock.getStatus(), stock.getComponentCatalog().getName());
        dailyComponentStockRepository.delete(stock);
    }

    /** Always-on items are never planned; a set meal goes only under a period that accepts set
     * meals (Breakfast, Lunch), so nothing is planned under All Day. */
    private static void requirePlannableIn(MealCatalog meal, MealPeriod period) {
        if (meal.getAvailability() == Availability.ALWAYS) {
            throw MenuRuleException.badRequest("ALWAYS_ON_ITEM_NOT_PLANNABLE",
                    "'" + meal.getName() + "' is always available and isn't planned.");
        }
        if (meal.getGroup() != period.getAccepts()) {
            throw MenuRuleException.badRequest("MEAL_NOT_ALLOWED_IN_PERIOD",
                    "'" + meal.getName() + "' can't be planned under " + period.getName() + ".");
        }
    }

    private static void requirePlanned(DailyPlanItemStatus status, String name) {
        if (status != DailyPlanItemStatus.PLANNED) {
            throw new InvalidMenuRequestException("'" + name + "' can't be changed or removed: its ingredients have "
                    + "already been reviewed" + (status == DailyPlanItemStatus.READY ? " and it is on sale." : "."));
        }
    }

    /** Records or corrects the actual portions prepared, moving the option to READY. Allowed any
     * time after the ingredient review, including after sales start, but never below what's
     * already been sold. An actual above planned is allowed (the frontend warns). */
    @Transactional
    public DailyPlanOptionDto recordActualPortions(Long id, RecordActualPortionsRequest request) {
        var option = dailyMealOptionRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        if (option.isAlwaysOn()) {
            throw MenuRuleException.badRequest("ALWAYS_ON_ITEM_NOT_PLANNABLE",
                    "'" + option.getName() + "' is always available: it has no portions to record.");
        }
        if (option.getStatus() == DailyPlanItemStatus.PLANNED) {
            throw new InvalidMenuRequestException(
                    "Ingredients must be reviewed before an actual can be recorded.");
        }

        var actual = request.getActualPortions();
        var updated = dailyMealOptionRepository.recordActualPortions(
                id, actual, DailyPlanItemStatus.PLANNED, DailyPlanItemStatus.READY);
        // The update cleared the persistence context, so this is a fresh read either way.
        option = dailyMealOptionRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        if (updated == 0) {
            throw new InvalidMenuRequestException("actualPortions (" + actual
                    + ") cannot be lower than the " + option.getSold() + " already sold.");
        }
        return mealMapper.toPlanDto(option);
    }

    /** Same rules as {@link #recordActualPortions}, for component stock. */
    @Transactional
    public DailyPlanComponentStockDto recordActualQuantity(Long id, RecordActualQuantityRequest request) {
        var stock = dailyComponentStockRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        if (stock.getStatus() == DailyPlanItemStatus.PLANNED) {
            throw new InvalidMenuRequestException(
                    "Ingredients must be reviewed before an actual can be recorded.");
        }

        var actual = request.getActualQuantity();
        var updated = dailyComponentStockRepository.recordActualQuantity(
                id, actual, DailyPlanItemStatus.PLANNED, DailyPlanItemStatus.READY);
        stock = dailyComponentStockRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
        if (updated == 0) {
            throw new InvalidMenuRequestException("actualQuantity (" + actual
                    + ") cannot be lower than the " + stock.getSold() + " already sold.");
        }
        return mealMapper.toPlanDto(stock);
    }

    /** The admin planning page's view: every item for the date/period, whatever its status. */
    @Transactional(readOnly = true)
    public DailyPlanResponseDto getDailyPlan(LocalDate date, String periodName) {
        var mealPeriod = resolvePeriod(periodName);
        var options = dailyMealOptionRepository.findByOptionDateAndMealPeriodIdOrderById(date, mealPeriod.getId())
                .stream().map(mealMapper::toPlanDto).toList();
        var componentStock = dailyComponentStockRepository.findByOptionDateAndMealPeriodIdOrderById(date, mealPeriod.getId())
                .stream().map(mealMapper::toPlanDto).toList();
        return new DailyPlanResponseDto(options, componentStock, mealPeriod.isPlannable());
    }

    /**
     * The POS menu — READY items only. Order creation enforces the same rule server-side. Today's
     * always-on options are created first (by MenuController, outside this read transaction).
     * With a period: that period's items, as before. Without:
     * the full till menu — every period open now, plus light meals and (Part B) beverages, sorted by
     * group, category and item. An always-on item whose meal is inactive (or no longer always-on)
     * is left out; one marked out today stays listed with outToday = true.
     */
    @Transactional(readOnly = true)
    public MenuTodayResponseDto getTodayMenu(String periodName) {
        var today = LocalDate.now(clock);
        var now = LocalTime.now(clock);

        List<DailyMealOption> options;
        List<DailyComponentStock> extras;
        if (periodName == null || periodName.isBlank()) {
            options = dailyMealOptionRepository.findByOptionDateAndStatusOrderById(today, DailyPlanItemStatus.READY).stream()
                    .filter(option -> option.getMealPeriod() == null || option.getMealPeriod().isActiveAt(now))
                    .sorted(TILL_ORDER)
                    .toList();
            extras = mealPeriodRepository.findAll().stream().filter(period -> period.isActiveAt(now))
                    .flatMap(period -> dailyComponentStockRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                            today, period.getId(), DailyPlanItemStatus.READY).stream())
                    .toList();
        } else {
            var mealPeriod = resolvePeriod(periodName);
            options = dailyMealOptionRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                    today, mealPeriod.getId(), DailyPlanItemStatus.READY);
            extras = dailyComponentStockRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                    today, mealPeriod.getId(), DailyPlanItemStatus.READY);
        }

        var response = new MenuTodayResponseDto();
        var listed = options.stream().filter(AlwaysOnMenuService::listed).toList();
        // Light meal stock for the till's warning chip: one ledger query for the whole menu.
        var stock = lightMealStockService.stockFor(listed.stream()
                .filter(DailyMealOption::isAlwaysOn).map(DailyMealOption::getMealCatalog).distinct().toList());
        response.setOptions(listed.stream().map(option -> {
            var dto = mealMapper.toDto(option);
            var mealStock = option.isAlwaysOn() ? stock.get(option.getMealCatalog().getId()) : null;
            if (mealStock != null) {
                dto.setStockStatus(mealStock.status());
                dto.setUnitsAvailable(mealStock.unitsAvailable());
            }
            return dto;
        }).toList());
        response.setAvailableExtras(extras.stream().map(mealMapper::toDto).toList());
        return response;
    }

    private MealPeriod resolvePeriod(String periodName) {
        return mealPeriodRepository.findByNameIgnoreCase(periodName)
                .orElseThrow(() -> new InvalidMenuRequestException("Unknown meal period: " + periodName));
    }
}
