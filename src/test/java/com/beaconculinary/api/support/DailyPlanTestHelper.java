package com.beaconculinary.api.support;

import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.DailyPlanItemStatus;

/** Puts a freshly created daily plan item straight on sale (READY, actual = planned) for tests
 * about selling rather than about the review/actuals flow itself — the real path goes through
 * confirm-ingredient-requirements (a Stock Request, Main Store stock, recipes) and then the
 * actual endpoint, which is covered by DailyPlanStatusIntegrationTests. */
public final class DailyPlanTestHelper {
    private DailyPlanTestHelper() {
    }

    public static void markReady(DailyMealOptionRepository repository, long id) {
        var option = repository.findById(id).orElseThrow();
        option.setStatus(DailyPlanItemStatus.READY);
        option.setActualPortions(option.getPlannedPortions());
        option.setPortionsRemaining(option.getPlannedPortions());
        repository.save(option);
    }

    public static void markReady(DailyComponentStockRepository repository, long id) {
        var stock = repository.findById(id).orElseThrow();
        stock.setStatus(DailyPlanItemStatus.READY);
        stock.setActualQuantity(stock.getBufferQuantity());
        stock.setBufferRemaining(stock.getBufferQuantity());
        repository.save(stock);
    }
}
