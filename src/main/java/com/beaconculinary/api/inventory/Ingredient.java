package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Stage 5 Part A — a physically-stocked raw material. Current stock is never a column here —
 * it is always derived as {@code SUM(IngredientStockMovement.quantity)} (see
 * {@code IngredientService#getStock}), consistent with every other ledger in this system. */
@Getter
@Setter
@Entity
@Table(name = "ingredients")
public class Ingredient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "unit")
    @Enumerated(EnumType.STRING)
    private IngredientUnit unit;

    @Column(name = "count_sheet_category")
    @Enumerated(EnumType.STRING)
    private CountSheetCategory countSheetCategory;

    @Column(name = "active")
    private boolean active = true;

    // Stage 5.2.2 — optional supplier/internal item code, free text, no uniqueness constraint
    // (codes may vary by supplier). Shown for reference only, in Ingredient Master and GRV's
    // item search.
    @Column(name = "item_code")
    private String itemCode;
}
