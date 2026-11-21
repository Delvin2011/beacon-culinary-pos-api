package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Stage 5.2.1 — a physical place raw-ingredient stock can sit at. T0-5c: one PRIMARY plus any
 * number of SECONDARY locations, managed at /admin/locations; flows find theirs through {@link
 * StockLocations}, never by name. */
@Getter
@Setter
@Entity
@Table(name = "locations")
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "active")
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private LocationKind kind = LocationKind.SECONDARY;

    @Column(name = "sort_order")
    private int sortOrder;

    // Audit (V115): null on the two seeded rows. No foreign key to users (users are hard-deleted).
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;

    public boolean isPrimary() {
        return kind == LocationKind.PRIMARY;
    }
}
