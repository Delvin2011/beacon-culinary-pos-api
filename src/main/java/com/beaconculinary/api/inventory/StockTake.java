package com.beaconculinary.api.inventory;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Stage 5.2.5 — a physical stock count submitted for one location, reviewed (approved or
 * rejected) by a {@code STOCK_ADMIN}/{@code ADMIN}. Deliberately not a {@link StockRequest}:
 * nobody is requesting anything here — a clerk reports what they physically counted, and review
 * is a binary credibility check ({@link StockTakeDecision}), never an edit of that observation.
 * Replaces the original Stage 5 direct-write {@code POST /admin/stock-takes}, now {@link
 * LegacyStockTake} — historical data only, nothing new writes there. */
@Getter
@Setter
@Entity
@Table(name = "stock_takes")
public class StockTake {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private User submittedBy;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private StockTakeStatus status = StockTakeStatus.SUBMITTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // Populated at review time only (approval comment or rejection reason) — submission itself
    // carries no note.
    @Column(name = "note")
    private String note;

    @OneToMany(mappedBy = "stockTake", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockTakeLine> lines = new ArrayList<>();
}
