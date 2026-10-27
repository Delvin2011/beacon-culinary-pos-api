package com.beaconculinary.api.accounts;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

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

    // Computed by the database from the id (PM0001, PM0002, ...) — see V94.
    @Generated(event = EventType.INSERT)
    @Column(name = "account_number", insertable = false, updatable = false)
    private String accountNumber;

    @Column(name = "name")
    private String name;

    @Column(name = "department")
    private String department;

    @Column(name = "contact_number")
    private String contactNumber;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "active")
    private boolean active = true;
}
