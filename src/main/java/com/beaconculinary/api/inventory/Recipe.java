package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalog;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Stage 5 Part B — a component's raw-ingredient bill of materials, written as a batch (e.g.
 * "10 portions needs 1.5kg rice") rather than pre-divided per-portion figures. One recipe per
 * {@link ComponentCatalog} (flat — no nested recipe-referencing-recipe). A component without a
 * recipe is simply excluded from ingredient-requirement calculations. */
@Getter
@Setter
@Entity
@Table(name = "recipes")
public class Recipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_catalog_id")
    private ComponentCatalog componentCatalog;

    @Column(name = "batch_size")
    private Integer batchSize;

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecipeLine> lines = new ArrayList<>();
}
