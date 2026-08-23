package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    List<PurchaseOrder> findAllWithLinesByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    Optional<PurchaseOrder> findWithLinesById(Long id);

    // Stage 5.2.3 — looks up the PO an approved ORDER-type StockRequest produced, via the nested
    // stockRequest.id property (no direct stockRequestId field on PurchaseOrder).
    Optional<PurchaseOrder> findByStockRequestId(Long stockRequestId);
}
