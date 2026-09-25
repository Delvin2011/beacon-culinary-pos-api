package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A daily pool of a component available as an extra for a given date + meal period — not linked to any specific {@link DailyMealOption}. */
@Getter
@Setter
@Entity
@Table(name = "daily_component_stock")
public class DailyComponentStock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_catalog_id")
    private ComponentCatalog componentCatalog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_period_id")
    private MealPeriod mealPeriod;

    @Column(name = "option_date")
    private LocalDate optionDate;

    // Snapshotted from ComponentCatalog at creation time.
    @Column(name = "extra_price")
    private BigDecimal extraPrice;

    @Column(name = "buffer_quantity")
    private Integer bufferQuantity;

    // Recorded once prepared; null until then. Sales count down from this, not bufferQuantity.
    @Column(name = "actual_quantity")
    private Integer actualQuantity;

    // actualQuantity - sold, kept as a counter so orders can decrement it atomically. Stays 0
    // until an actual is recorded, so nothing can be sold before the item is READY.
    @Column(name = "buffer_remaining")
    private Integer bufferRemaining;

    // Leaving PLANNED also means this row has contributed to a confirmed ingredient-requirement
    // calculation, so a later re-planning of the same day never sums it again.
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DailyPlanItemStatus status = DailyPlanItemStatus.PLANNED;

    /** Quantity sold so far (net of voids). Zero before an actual is recorded. */
    public int getSold() {
        return (actualQuantity == null ? 0 : actualQuantity) - bufferRemaining;
    }
}
