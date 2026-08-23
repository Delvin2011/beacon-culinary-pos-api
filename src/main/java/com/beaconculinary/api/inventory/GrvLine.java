package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Stage 5.2.2 — one ingredient's receipt within a {@link Grv} delivery, and the actual {@code
 * source_type = 'GRV'} target every RECEIVED {@link IngredientStockMovement} points at. {@code
 * quantityOrdered} is only populated when linked to a {@link PurchaseOrderLine} (derived from it
 * at creation time) — an ad-hoc line (no PO link) leaves it {@code null}, since there's nothing
 * to compute a receipt variance against. */
@Getter
@Setter
@Entity
@Table(name = "grv_lines")
public class GrvLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grv_id")
    private Grv grv;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_line_id")
    private PurchaseOrderLine purchaseOrderLine;

    @Column(name = "quantity_ordered")
    private BigDecimal quantityOrdered;

    @Column(name = "quantity_received")
    private BigDecimal quantityReceived;

    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit;
}
