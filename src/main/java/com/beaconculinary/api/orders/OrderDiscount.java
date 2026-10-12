package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A manager-approved discount given at checkout, before payment (at most one per order). Not
 * an adjustment: it reduces what the customer pays, and no money is ever handed back for it. */
@Getter
@Setter
@Entity
@Table(name = "order_discounts")
public class OrderDiscount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "discount_type")
    @Enumerated(EnumType.STRING)
    private DiscountType discountType;

    // The percentage (e.g. 10.00) or the fixed rand amount, as entered.
    @Column(name = "discount_value")
    private BigDecimal discountValue;

    // The rand amount taken off the pre-discount total.
    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "reason_code")
    @Enumerated(EnumType.STRING)
    private SaleDiscountReasonCode reasonCode;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorized_by")
    private User authorizedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
