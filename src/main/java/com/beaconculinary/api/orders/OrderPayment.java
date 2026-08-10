package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.Account;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Stage 4 Part A — one entry of an order's payment split: 1 row for a single-method order, 2
 * for a CASH+CARD split, always exactly 1 for ACCOUNT (mutually exclusive with CASH/CARD).
 * Replaces {@code Order}'s old single payment_method/amount_tendered/change_due/card_reference
 * fields (Stage 1.3/3) as the source of truth going forward. Never mutated after order
 * creation — later adjustments change {@code Order.total}, not this row. */
@Getter
@Setter
@Entity
@Table(name = "order_payments")
public class OrderPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "method")
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(name = "amount")
    private BigDecimal amount;

    // CASH only.
    @Column(name = "amount_tendered")
    private BigDecimal amountTendered;

    @Column(name = "change_due")
    private BigDecimal changeDue;

    // CARD only — whatever the physical card machine printed.
    @Column(name = "card_reference")
    private String cardReference;

    // ACCOUNT only.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;
}
