package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {
    Optional<Location> findByNameIgnoreCase(String name);

    /** T0-5c: the primary; V115 guarantees exactly one. */
    Optional<Location> findFirstByKind(LocationKind kind);

    /** T0-5c: display order everywhere locations are listed. */
    List<Location> findAllByOrderBySortOrderAscIdAsc();

    List<Location> findByActiveTrueOrderBySortOrderAscIdAsc();
}
