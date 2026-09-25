package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
@AllArgsConstructor
public class DailyPlanningService {
    private final MealPeriodRepository mealPeriodRepository;
    private final MealCatalogRepository mealCatalogRepository;
    private final ComponentCatalogRepository componentCatalogRepository;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final MealMapper mealMapper;
    private final Clock clock;

    @Transactional
    public DailyMealOptionDto createDailyOption(CreateDailyOptionRequest request) {
        var mealPeriod = mealPeriodRepository.findById(request.getMealPeriodId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealPeriodId does not exist."));
        var mealCatalog = mealCatalogRepository.findById(request.getMealCatalogId())
                .orElseThrow(() -> new InvalidMenuRequestException("mealCatalogId does not exist."));

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

    /** Records or corrects the actual portions prepared, moving the option to READY. Allowed any
     * time after the ingredient review, including after sales start, but never below what's
     * already been sold. An actual above planned is allowed (the frontend warns). */
    @Transactional
    public DailyPlanOptionDto recordActualPortions(Long id, RecordActualPortionsRequest request) {
        var option = dailyMealOptionRepository.findById(id).orElseThrow(DailyPlanItemNotFoundException::new);
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
        return new DailyPlanResponseDto(options, componentStock);
    }

    /** The POS menu — READY items only. Order creation enforces the same rule server-side. */
    @Transactional(readOnly = true)
    public MenuTodayResponseDto getTodayMenu(String periodName) {
        var mealPeriod = resolvePeriod(periodName);

        var today = LocalDate.now(clock);

        var options = dailyMealOptionRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                        today, mealPeriod.getId(), DailyPlanItemStatus.READY)
                .stream().map(mealMapper::toDto).toList();
        var availableExtras = dailyComponentStockRepository.findByOptionDateAndMealPeriodIdAndStatusOrderById(
                        today, mealPeriod.getId(), DailyPlanItemStatus.READY)
                .stream().map(mealMapper::toDto).toList();

        var response = new MenuTodayResponseDto();
        response.setOptions(options);
        response.setAvailableExtras(availableExtras);
        return response;
    }

    private MealPeriod resolvePeriod(String periodName) {
        return mealPeriodRepository.findByNameIgnoreCase(periodName)
                .orElseThrow(() -> new InvalidMenuRequestException("Unknown meal period: " + periodName));
    }
}
