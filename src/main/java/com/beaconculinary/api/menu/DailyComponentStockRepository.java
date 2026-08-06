package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyComponentStockRepository extends JpaRepository<DailyComponentStock, Long> {
    List<DailyComponentStock> findByOptionDateAndMealPeriodId(LocalDate optionDate, Long mealPeriodId);

    /**
     * Conditional decrement guarded by the WHERE clause — returns rows affected (0 or 1) so the
     * caller can detect an oversell attempt without a separate read-then-write race window.
     */
    @Modifying
    @Query("UPDATE DailyComponentStock s SET s.bufferRemaining = s.bufferRemaining - :quantity " +
            "WHERE s.id = :id AND s.bufferRemaining >= :quantity")
    int decrementBufferRemaining(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * Stage 2.6 — additive restoration on a VOID adjustment. No oversell risk here since we're
     * only ever increasing stock, so unlike the decrement there's no conditional WHERE guard.
     */
    @Modifying
    @Query("UPDATE DailyComponentStock s SET s.bufferRemaining = s.bufferRemaining + :quantity WHERE s.id = :id")
    void incrementBufferRemaining(@Param("id") Long id, @Param("quantity") int quantity);
}
