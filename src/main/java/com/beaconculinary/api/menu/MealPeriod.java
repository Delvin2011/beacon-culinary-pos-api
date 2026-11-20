package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

@Getter
@Setter
@Entity
@Table(name = "meal_periods")
public class MealPeriod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "all_day")
    private boolean allDay;

    // Which group may be planned or offered here (V96): Breakfast/Lunch take SET_MEALS, All Day
    // takes LIGHT_MEALS (always-on, never planned). Beverages have no period at all.
    @Enumerated(EnumType.STRING)
    @Column(name = "accepts")
    private CatalogGroup accepts = CatalogGroup.SET_MEALS;

    // T0-5a (V114): an inactive period is never open, isn't listed or offered, and takes no new
    // plan items. Past items, orders and reports keep it.
    @Column(name = "active")
    private boolean active = true;

    // List order of GET /meal-periods and the admin list (then id).
    @Column(name = "sort_order")
    private int sortOrder;

    /** Whether set meals are planned here; false for the light-meals period (All Day). */
    public boolean isPlannable() {
        return accepts == CatalogGroup.SET_MEALS;
    }

    /** Open at this site-local time: never while inactive; All Day always; otherwise start to end, both inclusive. */
    public boolean isActiveAt(LocalTime time) {
        if (!active) return false;
        if (allDay) return true;
        return !time.isBefore(startTime) && !time.isAfter(endTime);
    }
}
