package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockRequestRepository extends JpaRepository<StockRequest, Long> {
    // status/type/requestedById are all independently optional — requestedById is non-null only
    // when the caller is a STOCK_CLERK (own-requests-only visibility); null for STOCK_ADMIN/ADMIN
    // (see-all).
    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    @Query("SELECT sr FROM StockRequest sr WHERE " +
            "(:status IS NULL OR sr.status = :status) AND " +
            "(:type IS NULL OR sr.requestType = :type) AND " +
            "(:requestedById IS NULL OR sr.requestedBy.id = :requestedById) " +
            "ORDER BY sr.requestedAt DESC")
    List<StockRequest> search(@Param("status") StockRequestStatus status, @Param("type") StockRequestType type,
                               @Param("requestedById") Long requestedById);

    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    Optional<StockRequest> findWithLinesById(Long id);
}
