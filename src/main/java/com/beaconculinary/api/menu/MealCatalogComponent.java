package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Informational composition of a {@link MealCatalog} — for menu display only, not a pricing or stock relationship. */
@Getter
@Setter
@Entity
@Table(name = "meal_catalog_components")
public class MealCatalogComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_catalog_id")
    private MealCatalog mealCatalog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_catalog_id")
    private ComponentCatalog componentCatalog;
}
