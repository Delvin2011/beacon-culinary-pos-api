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
}
