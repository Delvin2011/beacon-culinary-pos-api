package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MealPeriodRepository extends JpaRepository<MealPeriod, Long> {
    Optional<MealPeriod> findByNameIgnoreCase(String name);

    /** The period light meals are offered under (All Day). */
    Optional<MealPeriod> findFirstByAcceptsOrderById(CatalogGroup accepts);
}
