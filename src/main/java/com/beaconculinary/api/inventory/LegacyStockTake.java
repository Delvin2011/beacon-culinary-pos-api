package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stage 5 Part F — a physical stock count reconciled against the derived ledger total, the same
 * expected-vs-counted variance pattern already proven for cash in Stage 2.5. Creates a
 * STOCK_TAKE_ADJUSTMENT {@link IngredientStockMovement} and is that movement's source.
 *
 * @deprecated Stage 5.2.5 replaces this one-step, {@code STOCK_ADMIN}-only, Main-Store-only
 * direct write with the two-step submit/review {@link StockTake} flow (location-selectable,
 * {@code STOCK_CLERK} can submit). This class/table is kept only for historical data — nothing
 * new should ever write here again. */
@Deprecated
@Getter
@Setter
@Entity
@Table(name = "legacy_stock_takes")
public class LegacyStockTake {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "counted_quantity")
    private BigDecimal countedQuantity;

    // countedQuantity - derivedStockAtTimeOfCount, stored so the count is a durable record even
    // though it can also be recomputed from the resulting movement.
    @Column(name = "variance")
    private BigDecimal variance;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
