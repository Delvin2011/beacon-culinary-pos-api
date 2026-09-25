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

    // Recorded once cooked; null until then. Sales count down from this, not plannedPortions.
    @Column(name = "actual_portions")
    private Integer actualPortions;

    // actualPortions - sold, kept as a counter so orders can decrement it atomically. Stays 0
    // until an actual is recorded, so nothing can be sold before the item is READY.
    @Column(name = "portions_remaining")
    private Integer portionsRemaining;

    // Leaving PLANNED also means this row has contributed to a confirmed ingredient-requirement
    // calculation, so a later re-planning of the same day never sums it again.
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DailyPlanItemStatus status = DailyPlanItemStatus.PLANNED;

    /** Portions sold so far (net of voids). Zero before an actual is recorded. */
    public int getSold() {
        return (actualPortions == null ? 0 : actualPortions) - portionsRemaining;
    }
}
