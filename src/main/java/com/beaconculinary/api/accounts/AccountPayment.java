package com.beaconculinary.api.accounts;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stage 4 Part B — money received against an account's outstanding balance, full or partial,
 * recorded by an admin. Never mutates {@link Account} directly — the balance is always derived
 * (see {@code AccountService#getBalance}). */
@Getter
@Setter
@Entity
@Table(name = "account_payments")
public class AccountPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
