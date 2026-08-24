package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.MealPeriod;
import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Stage 5 Revision — a request to move or write off stock (ISSUE or WASTE), submitted by a
 * {@code STOCK_CLERK} (or {@code STOCK_ADMIN}/{@code ADMIN} acting directly) and authorized by a
 * {@code STOCK_ADMIN}/{@code ADMIN} via {@code POST /stock-requests/{id}/action}. When {@code
 * source = DAILY_PLANNING}, this row is what {@code confirm-ingredient-requirements} now creates
 * instead of deducting stock directly — {@code dailyPlanDate}/{@code dailyPlanPeriod} carry the
 * traceability that {@code IngredientRequirementConfirmation} used to. */
@Getter
@Setter
@Entity
@Table(name = "stock_requests")
public class StockRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "request_type")
    @Enumerated(EnumType.STRING)
    private StockRequestType requestType;

    @Column(name = "source")
    @Enumerated(EnumType.STRING)
    private StockRequestSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @Column(name = "requested_at", insertable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private StockRequestStatus status = StockRequestStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actioned_by")
    private User actionedBy;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    @Column(name = "daily_plan_date")
    private LocalDate dailyPlanDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_plan_period_id")
    private MealPeriod dailyPlanPeriod;

    // Stage 5.2.4 — required for WASTE (validated at creation), unused by ISSUE (still fixed
    // Main Store -> Kitchen) and ORDER (not location-specific). Fixed at submission; approving a
    // request can adjust quantities but never the location.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @OneToMany(mappedBy = "stockRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockRequestLine> lines = new ArrayList<>();
}
