package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Stage 5 Revision — one ingredient's requested quantity within a {@link StockRequest}. {@code
 * actionedQuantity} stays {@code null} until a {@code STOCK_ADMIN}/{@code ADMIN} authorizes the
 * request; {@code reason} is only meaningful for {@code WASTE}-type requests (free text, same
 * purpose as the existing direct-waste reason field). */
@Getter
@Setter
@Entity
@Table(name = "stock_request_lines")
public class StockRequestLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_request_id")
    private StockRequest stockRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(name = "requested_quantity")
    private BigDecimal requestedQuantity;

    @Column(name = "actioned_quantity")
    private BigDecimal actionedQuantity;

    @Column(name = "reason")
    private String reason;
}
