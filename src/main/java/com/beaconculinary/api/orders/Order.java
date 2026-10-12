package com.beaconculinary.api.orders;

import com.beaconculinary.api.shifts.Shift;
import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_number")
    private Integer orderNumber;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id")
    private User cashier;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "subtotal")
    private BigDecimal subtotal;

    @Column(name = "total")
    private BigDecimal total;

    // What the meals + extras cost, before any discount at sale. Equals originalTotal when there
    // was no discount (and for every order placed before discounts at sale existed).
    @Column(name = "pre_discount_total")
    private BigDecimal preDiscountTotal;

    // The manager-approved discount given at checkout; 0 when none.
    @Column(name = "sale_discount")
    private BigDecimal saleDiscount = BigDecimal.ZERO;

    // Immutable snapshot of the total at creation, for audit — unlike total, this never changes.
    // = preDiscountTotal - saleDiscount = the sum of the payments.
    @Column(name = "original_total")
    private BigDecimal originalTotal;

    // Guards against an EXTRAS_ONLY adjustment being applied twice to the same order.
    @Column(name = "extras_adjusted")
    private boolean extrasAdjusted = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "print_failed")
    private boolean printFailed = false;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderAdjustment> adjustments = new ArrayList<>();

    // Stage 4 Part A — one row per payment method used (1 entry for a single-method order, up
    // to 2 for a CASH+CARD split, always exactly 1 for ACCOUNT). Replaces the old single
    // payment_method/amount_tendered/change_due/card_reference fields (Stage 1.3/3); those
    // columns remain in the orders table, unused, flagged for a future cleanup.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderPayment> payments = new ArrayList<>();

    // The discount at sale's details and approval, or null when there was none.
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private OrderDiscount discount;

    /** True when a discount was given at checkout — such an order can't be refunded. */
    public boolean discountedAtSale() {
        return saleDiscount != null && saleDiscount.signum() > 0;
    }
}
