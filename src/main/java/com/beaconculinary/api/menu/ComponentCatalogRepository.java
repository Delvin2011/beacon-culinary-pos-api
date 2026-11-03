package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ComponentCatalogRepository extends JpaRepository<ComponentCatalog, Long> {
    /** The catalogue search: every filter is optional (null/false = don't filter). */
    @Query("SELECT c FROM ComponentCatalog c LEFT JOIN c.category cat "
            + "WHERE (:categoryId IS NULL OR cat.id = :categoryId) "
            + "AND (:uncategorised = false OR cat IS NULL) "
            + "AND (:name IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) "
            + "ORDER BY c.name")
    List<ComponentCatalog> search(@Param("categoryId") Long categoryId,
                                  @Param("uncategorised") boolean uncategorised,
                                  @Param("name") String name);

    List<ComponentCatalog> findAllByIdIn(List<Long> ids);

    Optional<ComponentCatalog> findByNameIgnoreCase(String name);
}
