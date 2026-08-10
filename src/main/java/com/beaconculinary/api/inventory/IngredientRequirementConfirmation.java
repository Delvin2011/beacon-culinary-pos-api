package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.MealPeriod;
import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Stage 5 Part C — one confirmed batch of "POST .../confirm-ingredient-requirements", for a
 * single date + meal period. Every CONSUMED_FOR_PREP {@link IngredientStockMovement} produced by
 * that confirmation points back at this row for traceability. Once created, the day's plan for
 * that date/period is locked (its contributing DailyMealOption/DailyComponentStock rows are
 * marked {@code ingredientsReviewed = true}) and is never reopened by this stage. */
@Getter
@Setter
@Entity
@Table(name = "ingredient_requirement_confirmations")
public class IngredientRequirementConfirmation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "planning_date")
    private LocalDate planningDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_period_id")
    private MealPeriod mealPeriod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private User confirmedBy;

    @Column(name = "confirmed_at", insertable = false, updatable = false)
    private LocalDateTime confirmedAt;
}
