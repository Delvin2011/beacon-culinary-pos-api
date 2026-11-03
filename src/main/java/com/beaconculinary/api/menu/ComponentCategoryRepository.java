package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ComponentCategoryRepository extends JpaRepository<ComponentCategory, Long> {
    boolean existsByNameIgnoreCase(String name);

    Optional<ComponentCategory> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /** Active components per category, for the list's counts and the deactivate guard. */
    interface CategoryCount {
        Long getCategoryId();

        long getCount();
    }

    @Query("SELECT c.category.id AS categoryId, COUNT(c) AS count FROM ComponentCatalog c "
            + "WHERE c.active = true AND c.category IS NOT NULL GROUP BY c.category.id")
    List<CategoryCount> countActiveComponentsByCategory();
}
