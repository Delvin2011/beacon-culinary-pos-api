package com.beaconculinary.api.menu;

import java.time.Instant;
import java.time.LocalTime;

/** T0-5a: response shapes of /admin/meal-periods (contract sections 2 and 3). */
public final class MealPeriodAdminDtos {
    private MealPeriodAdminDtos() {
    }

    /** A period as the admin sees it: the public fields plus active, sortOrder and lastChange. */
    public record AdminMealPeriod(long id, String name, LocalTime startTime, LocalTime endTime, boolean allDay,
                                  CatalogGroup accepts, boolean plannable, boolean active, int sortOrder,
                                  LastChange lastChange) {
        static AdminMealPeriod of(MealPeriod period, LastChange lastChange) {
            return new AdminMealPeriod(period.getId(), period.getName(), period.getStartTime(), period.getEndTime(),
                    period.isAllDay(), period.getAccepts(), period.isPlannable(), period.isActive(),
                    period.getSortOrder(), lastChange);
        }
    }

    public record LastChange(String action, String byName, Instant at) {
    }

    /** The values a history entry records before and after a change. */
    public record Values(String name, LocalTime startTime, LocalTime endTime, int sortOrder, boolean active) {
        static Values of(MealPeriod period) {
            return new Values(period.getName(), period.getStartTime(), period.getEndTime(), period.getSortOrder(),
                    period.isActive());
        }
    }

    /** One history entry; before is null for CREATE. */
    public record HistoryEntry(long id, long periodId, String action, Values before, Values after, Instant changedAt,
                               long changedById, String changedByName) {
    }
}
