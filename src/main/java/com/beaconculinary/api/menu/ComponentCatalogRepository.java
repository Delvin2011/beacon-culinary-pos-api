package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComponentCatalogRepository extends JpaRepository<ComponentCatalog, Long> {
    List<ComponentCatalog> findAllByIdIn(List<Long> ids);

    Optional<ComponentCatalog> findByNameIgnoreCase(String name);
}
