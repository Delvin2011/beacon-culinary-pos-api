package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GrvRepository extends JpaRepository<Grv, Long> {
    // Stage 5.2.2 — ingredient/date-range/purchaseOrderId are all independently optional now
    // (ingredient/date-range filtering "moved" from the header to a line join, purchaseOrderId
    // is new), so a single null-coalescing query replaces what would otherwise be a combinatorial
    // explosion of finder methods.
    @EntityGraph(attributePaths = {"lines", "lines.ingredient", "lines.purchaseOrderLine"})
    @Query("SELECT DISTINCT g FROM Grv g JOIN g.lines l WHERE " +
            "(:ingredientId IS NULL OR l.ingredient.id = :ingredientId) AND " +
            "(:from IS NULL OR g.receivedAt >= :from) AND " +
            "(:to IS NULL OR g.receivedAt <= :to) AND " +
            "(:purchaseOrderId IS NULL OR g.purchaseOrder.id = :purchaseOrderId) " +
            "ORDER BY g.receivedAt DESC")
    List<Grv> search(@Param("ingredientId") Long ingredientId, @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to, @Param("purchaseOrderId") Long purchaseOrderId);

    @EntityGraph(attributePaths = {"lines", "lines.ingredient", "lines.purchaseOrderLine"})
    Optional<Grv> findWithLinesById(Long id);
}
