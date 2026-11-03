package com.beaconculinary.api.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A node of the catalogue tree (V96). Two levels: the fixed top-level groups ("Set meals",
 * "Light meals", "Beverages") and subcategories under any of them (under "Set meals" since V101:
 * Breakfast, Lunch). A meal may sit in "Set meals" itself or in a subcategory, never on the
 * "Light meals" or "Beverages" group row. "Set meals" stays assignable so meals not yet sorted
 * into a subcategory remain valid.
 */
@Getter
@Setter
@Entity
@Table(name = "catalog_categories")
public class CatalogCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CatalogCategory parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_code")
    private CatalogGroup group;

    @Column(name = "name")
    private String name;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "active")
    private boolean active = true;

    public boolean isTopLevel() {
        return parent == null;
    }

    /** Whether a meal can be assigned here: "Set meals" itself, or any subcategory. */
    public boolean isAssignable() {
        return !isTopLevel() || group == CatalogGroup.SET_MEALS;
    }

    /** Whether subcategories can be added under this node: any top-level group. */
    public boolean acceptsSubcategories() {
        return isTopLevel();
    }
}
