package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    // Always a leaf of the catalogue tree (V96); decides the meal's group and availability.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CatalogCategory category;

    // "Out today": the day it was marked out, so it clears itself the next day. ALWAYS items only.
    @Column(name = "out_on")
    private LocalDate outOn;

    // KITCHEN for set meals and light meals; BAR or NONE for beverages (V97).
    @Enumerated(EnumType.STRING)
    @Column(name = "station")
    private Station station = Station.KITCHEN;

    // Light meal stock: LOW when the Kitchen can make fewer units than this (V99).
    @Column(name = "low_stock_units")
    private int lowStockUnits = 10;

    @OneToMany(mappedBy = "mealCatalog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MealCatalogComponent> components = new ArrayList<>();

    public CatalogGroup getGroup() {
        return category.getGroup();
    }

    public Availability getAvailability() {
        return getGroup().availability();
    }

    public boolean isOutOn(LocalDate day) {
        return day.equals(outOn);
    }
}
