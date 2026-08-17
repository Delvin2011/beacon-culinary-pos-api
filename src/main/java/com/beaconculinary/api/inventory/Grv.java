package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stage 5 Part D — a goods-received record. Creates a RECEIVED {@link IngredientStockMovement}
 * and is that movement's source, carrying the cost history: an ingredient's "current cost" is
 * derived as the most recent GRV's {@code costPerUnit}, never overwritten directly onto {@link
 * Ingredient}. */
@Getter
@Setter
@Entity
@Table(name = "grv")
public class Grv {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "received_at", insertable = false, updatable = false)
    private LocalDateTime receivedAt;
}
