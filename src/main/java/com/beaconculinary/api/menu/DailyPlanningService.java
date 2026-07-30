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
        option.setPortionsRemaining(request.getPlannedPortions());

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
        stock.setBufferRemaining(request.getBufferQuantity());

        dailyComponentStockRepository.save(stock);
        return mealMapper.toDto(stock);
    }

    @Transactional(readOnly = true)
    public MenuTodayResponseDto getTodayMenu(String periodName) {
        var mealPeriod = mealPeriodRepository.findByNameIgnoreCase(periodName)
                .orElseThrow(() -> new InvalidMenuRequestException("Unknown meal period: " + periodName));

        var today = LocalDate.now(clock);

        var options = dailyMealOptionRepository.findByOptionDateAndMealPeriodId(today, mealPeriod.getId())
                .stream().map(mealMapper::toDto).toList();
        var availableExtras = dailyComponentStockRepository.findByOptionDateAndMealPeriodId(today, mealPeriod.getId())
                .stream().map(mealMapper::toDto).toList();

        var response = new MenuTodayResponseDto();
        response.setOptions(options);
        response.setAvailableExtras(availableExtras);
        return response;
    }
}
