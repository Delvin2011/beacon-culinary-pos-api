package com.beaconculinary.api.admin;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Stage 3.3 — a longer-lived, reusable manager-PIN authorization for the cashier terminal's
 * management menu (Void/Refund/Discount/Cashup Summary), unlike {@link AuthorizationToken}'s
 * single-use, 60-second window. Never consumed — stays valid for repeated use until its own
 * expiry, per {@link AdminAuthorizationService#validateSessionToken}. */
@Getter
@Setter
@Entity
@Table(name = "authorization_sessions")
public class AuthorizationSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_token")
    private String sessionToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private User admin;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
