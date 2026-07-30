package com.beaconculinary.api.shifts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    Optional<Shift> findFirstByCashierIdAndStatus(Long cashierId, ShiftStatus status);
}
