package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "meal_catalog")
public class MealCatalog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "active")
    private boolean active = true;

    @OneToMany(mappedBy = "mealCatalog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MealCatalogComponent> components = new ArrayList<>();
}
