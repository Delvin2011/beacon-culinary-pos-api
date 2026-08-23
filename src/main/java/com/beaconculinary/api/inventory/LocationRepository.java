package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {
    Optional<Location> findByNameIgnoreCase(String name);
}
