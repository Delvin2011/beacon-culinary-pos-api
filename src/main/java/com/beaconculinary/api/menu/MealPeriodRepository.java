package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MealPeriodRepository extends JpaRepository<MealPeriod, Long> {
    /** Active periods in list order (GET /meal-periods). */
    List<MealPeriod> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** Names are unique among active periods only (T0-5a): the active one, else the newest inactive one. */
    Optional<MealPeriod> findFirstByNameIgnoreCaseOrderByActiveDescIdDesc(String name);

    /** A period by name, ignoring case; prefers the active one. Used where the API takes a period name. */
    default Optional<MealPeriod> findByName(String name) {
        return findFirstByNameIgnoreCaseOrderByActiveDescIdDesc(name);
    }

    /** The period light meals are offered under (All Day). */
    Optional<MealPeriod> findFirstByAcceptsOrderById(CatalogGroup accepts);
}
