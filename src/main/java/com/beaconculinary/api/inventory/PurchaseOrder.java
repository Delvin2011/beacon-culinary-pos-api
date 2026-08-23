package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Stage 5 Part G — deliberately lightweight, purely informational purchase ordering. No
 * approval workflow, and not required to link to a later {@link Grv} — receiving stock and
 * having placed an order are independent actions in this stage. */
@Getter
@Setter
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // Stage 5.2.3 — set when this PO was produced by approving an ORDER-type StockRequest;
    // null for the unchanged direct-admin-creates-a-PO path.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_request_id")
    private StockRequest stockRequest;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseOrderLine> lines = new ArrayList<>();
}
