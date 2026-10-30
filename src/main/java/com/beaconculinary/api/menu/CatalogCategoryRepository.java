package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategory, Long> {
    boolean existsByParentIdAndNameIgnoreCase(Long parentId, String name);

    boolean existsByParentIdAndNameIgnoreCaseAndIdNot(Long parentId, String name, Long id);

    Optional<CatalogCategory> findByParentIsNullAndGroup(CatalogGroup group);

    /** Active meals per category, for the tree's item counts and the deactivate guard. */
    interface CategoryCount {
        Long getCategoryId();

        long getCount();
    }

    @Query("SELECT m.category.id AS categoryId, COUNT(m) AS count FROM MealCatalog m WHERE m.active = true GROUP BY m.category.id")
    List<CategoryCount> countActiveMealsByCategory();
}
