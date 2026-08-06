package com.beaconculinary.api.shifts;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "shifts")
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id")
    private User cashier;

    @Column(name = "opening_float")
    private BigDecimal openingFloat;

    @Column(name = "opened_at", insertable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ShiftStatus status;

    @Column(name = "closing_cash")
    private BigDecimal closingCash;

    @Column(name = "expected_cash")
    private BigDecimal expectedCash;

    @Column(name = "variance")
    private BigDecimal variance;

    @Column(name = "variance_reason_code")
    @Enumerated(EnumType.STRING)
    private ShiftVarianceReasonCode varianceReasonCode;

    @Column(name = "variance_note")
    private String varianceNote;

    // The admin whose PIN authorized a nonzero variance close.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variance_authorized_by")
    private User varianceAuthorizedBy;
}
