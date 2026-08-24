package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockTakeRepository extends JpaRepository<StockTake, Long> {
    // status/locationId/submittedById are all independently optional — submittedById is
    // non-null only when the caller is a STOCK_CLERK (own-submissions-only visibility); null for
    // STOCK_ADMIN/ADMIN (see-all). Same pattern as StockRequestRepository.search.
    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    @Query("SELECT st FROM StockTake st WHERE " +
            "(:status IS NULL OR st.status = :status) AND " +
            "(:locationId IS NULL OR st.location.id = :locationId) AND " +
            "(:submittedById IS NULL OR st.submittedBy.id = :submittedById) " +
            "ORDER BY st.submittedAt DESC")
    List<StockTake> search(@Param("status") StockTakeStatus status, @Param("locationId") Long locationId,
                            @Param("submittedById") Long submittedById);

    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    Optional<StockTake> findWithLinesById(Long id);
}
