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

    @Column(name = "buffer_remaining")
    private Integer bufferRemaining;

    // Stage 5 Part C — set once this row has contributed to a confirmed ingredient-requirement
    // deduction, so a later re-planning of the same day never sums it again.
    @Column(name = "ingredients_reviewed")
    private boolean ingredientsReviewed;
}
