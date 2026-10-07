package com.beaconculinary.api.shifts;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long>, JpaSpecificationExecutor<Shift> {
    Optional<Shift> findFirstByCashierIdAndStatus(Long cashierId, ShiftStatus status);

    Optional<Shift> findFirstByStatus(ShiftStatus status);

    /**
     * POS Oversight B3: the till's open shift, read under a shared lock held to the end of the
     * transaction (HOLDLOCK). Used wherever money is attached to the open shift (order creation,
     * adjustments) so it serialises against {@link #lockForClose}: either the close waits for
     * this transaction and its snapshot includes the new row, or this read waits for the close
     * and finds no open shift. Native SQL so the lock hints are explicit rather than dialect-mapped.
     */
    @Query(value = "SELECT * FROM shifts WITH (HOLDLOCK, ROWLOCK) WHERE status = 'OPEN'", nativeQuery = true)
    Optional<Shift> findOpenShiftWithSharedLock();

    /**
     * POS Oversight B3: takes an exclusive lock on the shift row, held to the end of the
     * transaction, before a close computes expected cash. XLOCK rather than UPDLOCK: an update
     * lock is compatible with the shared lock above, which would let an order commit between
     * the close's calculation and its write.
     */
    @Query(value = "SELECT id FROM shifts WITH (XLOCK, ROWLOCK) WHERE id = :id", nativeQuery = true)
    Optional<Long> lockForClose(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "cashier")
    Page<Shift> findAll(Specification<Shift> spec, Pageable pageable);
}
