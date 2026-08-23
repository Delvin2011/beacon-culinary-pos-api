package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stage 5 Part E — a raw-ingredient loss (spoilage, breakage, etc.), recorded so it can be
 * distinguished from planning-time consumption. Creates a WASTED {@link IngredientStockMovement}
 * and is that movement's source. */
@Getter
@Setter
@Entity
@Table(name = "waste_entries")
public class WasteEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "quantity")
    private BigDecimal quantity;

    // Stage 5.2.4 — required going forward; historical rows backfilled to Main Store.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "reason")
    private String reason;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
