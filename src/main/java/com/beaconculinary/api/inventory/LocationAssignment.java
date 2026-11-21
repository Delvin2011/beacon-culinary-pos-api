package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** T0-5c: the location one {@link LocationRole} uses (V115 location_assignments). Always an active
 * SECONDARY; the admin service checks that on every change and before a deactivation. */
@Getter
@Setter
@Entity
@Table(name = "location_assignments")
public class LocationAssignment {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private LocationRole role;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;
}
