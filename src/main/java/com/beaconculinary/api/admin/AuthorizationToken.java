package com.beaconculinary.api.admin;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A short-lived, single-use manager-PIN authorization, decoupled from the cashier's own
 * session so an admin can approve an action at the cashier's terminal without logging the
 * cashier out. Consumed (deleted) by {@link AdminAuthorizationService#consumeToken}. */
@Getter
@Setter
@Entity
@Table(name = "authorization_tokens")
public class AuthorizationToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "token")
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private User admin;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "used")
    private boolean used;
}
