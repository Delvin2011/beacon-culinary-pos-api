package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Stage 5.2.1 — a physical place raw-ingredient stock can sit at. Seeded reference data (Main
 * Store, Kitchen) — no endpoint to add a third one yet. */
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
}
