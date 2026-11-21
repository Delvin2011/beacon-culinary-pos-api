package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationAssignmentRepository extends JpaRepository<LocationAssignment, LocationRole> {
    List<LocationAssignment> findByLocationId(Long locationId);
}
