package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Stage 5.2.5 — one ingredient's physical count within a {@link StockTake}. {@code
 * expectedQuantity}/{@code unitCost}/{@code varianceQuantity}/{@code varianceValue} are
 * snapshotted at submission and never recalculated later — the honest record of what was true at
 * count time. {@code appliedAdjustmentQuantity} is set only on approval, recomputed against
 * current stock at that moment — it can legitimately differ from {@code varianceQuantity} if
 * other stock activity happened between submission and review; both are kept, on purpose. */
@Getter
@Setter
@Entity
@Table(name = "stock_take_lines")
public class StockTakeLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_take_id")
    private StockTake stockTake;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "expected_quantity")
    private BigDecimal expectedQuantity;

    @Column(name = "actual_quantity")
    private BigDecimal actualQuantity;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;

    @Column(name = "variance_quantity")
    private BigDecimal varianceQuantity;

    @Column(name = "variance_value")
    private BigDecimal varianceValue;

    @Column(name = "applied_adjustment_quantity")
    private BigDecimal appliedAdjustmentQuantity;
}
