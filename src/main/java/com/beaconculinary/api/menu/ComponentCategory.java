package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A flat grouping for meal components (Meat, Chicken, Fish, Starch, ...), seeded in V100. Used to
 * group and filter the component catalogue. No delete: retire one with active=false. */
@Getter
@Setter
@Entity
@Table(name = "component_categories")
public class ComponentCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "active")
    private boolean active = true;
}
