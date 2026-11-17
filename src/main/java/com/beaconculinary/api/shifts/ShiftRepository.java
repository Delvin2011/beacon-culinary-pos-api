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
import java.util.UUID;

public interface ShiftRepository extends JpaRepository<Shift, Long>, JpaSpecificationExecutor<Shift> {
    Optional<Shift> findFirstByCashierIdAndStatus(Long cashierId, ShiftStatus status);

    /** Any open shift, on any till. Since O2 several can be open: never use this to decide where a sale,
     * an adjustment or a till's status goes (use findOpenOnTill / findOpenOnTillWithSharedLock). */
    Optional<Shift> findFirstByStatus(ShiftStatus status);

    // O2: a till's open shift. The unregistered till is the one with no device.
    Optional<Shift> findFirstByDeviceIdAndStatus(UUID deviceId, ShiftStatus status);

    Optional<Shift> findFirstByDeviceIdIsNullAndStatus(ShiftStatus status);

    /** O2: the open shift of a till (null = the unregistered till), without a lock. */
    default Optional<Shift> findOpenOnTill(UUID deviceId) {
        return deviceId == null ? findFirstByDeviceIdIsNullAndStatus(ShiftStatus.OPEN)
                : findFirstByDeviceIdAndStatus(deviceId, ShiftStatus.OPEN);
    }

    /**
     * POS Oversight B3, per till since O2: a till's open shift, read under a shared lock held to the end of the
     * transaction (HOLDLOCK). Used wherever money is attached to the open shift (order creation,
     * adjustments) so it serialises against {@link #lockForClose}: either the close waits for
     * this transaction and its snapshot includes the new row, or this read waits for the close
     * and finds no open shift. Native SQL so the lock hints are explicit rather than dialect-mapped.
     */
    default Optional<Shift> findOpenOnTillWithSharedLock(UUID deviceId) {
        return deviceId == null ? findOpenOnUnregisteredTillWithSharedLock()
                : findOpenOnRegisteredTillWithSharedLock(deviceId.toString());
    }

    @Query(value = "SELECT * FROM shifts WITH (HOLDLOCK, ROWLOCK) WHERE status = 'OPEN' AND device_id = :deviceId", nativeQuery = true)
    Optional<Shift> findOpenOnRegisteredTillWithSharedLock(@Param("deviceId") String deviceId);

    @Query(value = "SELECT * FROM shifts WITH (HOLDLOCK, ROWLOCK) WHERE status = 'OPEN' AND device_id IS NULL", nativeQuery = true)
    Optional<Shift> findOpenOnUnregisteredTillWithSharedLock();

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
