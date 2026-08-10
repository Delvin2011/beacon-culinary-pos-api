package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTakeRepository extends JpaRepository<StockTake, Long> {
}
