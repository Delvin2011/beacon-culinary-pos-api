package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "daily_meal_options")
public class DailyMealOption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_period_id")
    private MealPeriod mealPeriod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_catalog_id")
    private MealCatalog mealCatalog;

    @Column(name = "option_date")
    private LocalDate optionDate;

    // Snapshotted from MealCatalog at creation time — a later catalog price/name change must
    // never retroactively rewrite an already-planned or already-sold day.
    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "planned_portions")
    private Integer plannedPortions;

    @Column(name = "portions_remaining")
    private Integer portionsRemaining;
}
