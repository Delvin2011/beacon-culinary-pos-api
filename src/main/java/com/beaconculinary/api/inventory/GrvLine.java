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

    // The ledger cost: always the VAT-inclusive unit cost (4 dp), whatever basis the invoice used.
    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit;

    // GRV VAT handling: the unit price exactly as written on the invoice, the VAT code and rate
    // snapshotted at capture, and both bases. On legacy lines (before V88) only
    // unitPriceAsInvoiced, unitCostInclVat and lineInclVat are filled; the VAT split stays null.
    @Column(name = "unit_price_as_invoiced")
    private BigDecimal unitPriceAsInvoiced;

    @Column(name = "vat_code")
    @Enumerated(EnumType.STRING)
    private VatCode vatCode;

    @Column(name = "vat_rate")
    private BigDecimal vatRate;

    @Column(name = "unit_cost_excl_vat")
    private BigDecimal unitCostExclVat;

    @Column(name = "unit_cost_incl_vat")
    private BigDecimal unitCostInclVat;

    @Column(name = "line_excl_vat")
    private BigDecimal lineExclVat;

    @Column(name = "line_vat")
    private BigDecimal lineVat;

    @Column(name = "line_incl_vat")
    private BigDecimal lineInclVat;
}
