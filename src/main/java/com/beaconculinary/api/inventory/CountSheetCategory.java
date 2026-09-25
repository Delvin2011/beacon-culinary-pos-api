package com.beaconculinary.api.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A count sheet grouping for an ingredient (POULTRY, MEAT, DRYSTOCK, BEVERAGES, ...). Reference
 * data seeded in V78 — no longer a fixed enum, so new categories can be added without a code
 * change. */
@Getter
@Setter
@Entity
@Table(name = "count_sheet_categories")
public class CountSheetCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "active")
    private boolean active = true;
}
