package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stage 5 — the single append-only ledger backing every raw-ingredient stock figure in this
 * system. Current stock is always {@code SUM(quantity)} for an ingredient, never a stored or
 * mutable field — same pattern as {@code OrderStatusEvent}/{@code AccountPayment}. {@code
 * sourceType}/{@code sourceId} point at whichever Part A-G record caused the movement (a {@link
 * Grv}, an {@code IngredientRequirementConfirmation}, a {@link WasteEntry}, or a {@link
 * StockTake}), for traceability. */
@Getter
@Setter
@Entity
@Table(name = "ingredient_stock_movements")
public class IngredientStockMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "movement_type")
    @Enumerated(EnumType.STRING)
    private MovementType movementType;

    // Signed — positive in (RECEIVED), negative out (CONSUMED_FOR_PREP, WASTED); a
    // STOCK_TAKE_ADJUSTMENT may be either sign depending on the variance direction.
    @Column(name = "quantity")
    private BigDecimal quantity;

    // RECEIVED only — the per-unit cost snapshotted from the Grv that created this movement.
    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit;

    @Column(name = "source_type")
    @Enumerated(EnumType.STRING)
    private MovementSourceType sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
