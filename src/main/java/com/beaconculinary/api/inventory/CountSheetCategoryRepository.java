package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountSheetCategoryRepository extends JpaRepository<CountSheetCategory, Long> {
    Optional<CountSheetCategory> findByNameIgnoreCase(String name);
}
