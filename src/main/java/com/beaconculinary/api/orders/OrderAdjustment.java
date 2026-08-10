package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.Account;
import com.beaconculinary.api.shifts.Shift;
import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "order_adjustments")
public class OrderAdjustment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    // The shift open at the moment this adjustment was authorized — may differ from
    // order.shift when the adjustment is processed in a later shift than the original sale
    // (Stage 2.5 cash-drawer attribution).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "scope")
    @Enumerated(EnumType.STRING)
    private OrderAdjustmentScope scope;

    @Column(name = "action")
    @Enumerated(EnumType.STRING)
    private OrderAdjustmentAction action;

    @Column(name = "reason_code")
    @Enumerated(EnumType.STRING)
    private OrderAdjustmentReasonCode reasonCode;

    @Column(name = "note")
    private String note;

    @Column(name = "amount")
    private BigDecimal amount;

    // DISCOUNT rows only (Stage 3.2) — null for VOID/REFUND.
    @Column(name = "discount_type")
    @Enumerated(EnumType.STRING)
    private DiscountType discountType;

    @Column(name = "discount_value")
    private BigDecimal discountValue;

    // Stage 4 Part C — how this adjustment's money is actually paid out, computed automatically
    // from whether the order's payment included ACCOUNT. Never client-supplied.
    @Column(name = "refund_method")
    @Enumerated(EnumType.STRING)
    private RefundMethod refundMethod;

    // Populated only when refund_method = ACCOUNT_BALANCE, copied from the order's OrderPayment.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    // The cashier who initiated the adjustment.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    // The admin whose PIN authorized it.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorized_by")
    private User authorizedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
