package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Stage 5.2.2 — a goods-received delivery: one invoice number, one supplier, covering however
 * many ingredient lines that invoice actually contained. Each {@link GrvLine} is its own {@link
 * IngredientStockMovement} source (not this header), so a movement always traces back to the
 * specific line item that produced it, not just the delivery it was part of. */
@Getter
@Setter
@Entity
@Table(name = "grv")
public class Grv {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    // Optional — an ad-hoc purchase (no purchase order) still has a real supplier invoice.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "received_at", insertable = false, updatable = false)
    private LocalDateTime receivedAt;

    // Stage 5.2.6 — populated only by a same-day PATCH /admin/grv/{id} correction; null on every
    // GRV that has never been edited. A GRV otherwise stays an immutable receiving record.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edited_by")
    private User editedBy;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @OneToMany(mappedBy = "grv", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GrvLine> lines = new ArrayList<>();
}
