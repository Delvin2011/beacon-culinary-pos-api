package com.beaconculinary.api.accounts;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Stage 4 Part B — a canteen account (e.g. a department or company) that can pay for orders on
 * credit, settled later via {@link AccountPayment}. */
@Getter
@Setter
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "active")
    private boolean active = true;
}
