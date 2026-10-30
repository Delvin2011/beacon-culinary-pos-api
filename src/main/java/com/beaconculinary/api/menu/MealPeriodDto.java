package com.beaconculinary.api.menu;

import lombok.Data;

import java.time.LocalTime;

@Data
public class MealPeriodDto {
    private Long id;
    private String name;
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean allDay;
    private CatalogGroup accepts;
    // false for the period that takes light meals (All Day): nothing is planned there.
    private boolean plannable;
}
