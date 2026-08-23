package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {
}
