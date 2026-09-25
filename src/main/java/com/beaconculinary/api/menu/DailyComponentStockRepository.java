package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyComponentStockRepository extends JpaRepository<DailyComponentStock, Long> {
    List<DailyComponentStock> findByOptionDateAndMealPeriodIdOrderById(LocalDate optionDate, Long mealPeriodId);

    /** With status PLANNED: the set an ingredient-requirements calculation sums over, excluding
     * rows already covered by a confirmation. With READY: what the POS may sell. */
    List<DailyComponentStock> findByOptionDateAndMealPeriodIdAndStatusOrderById(
            LocalDate optionDate, Long mealPeriodId, DailyPlanItemStatus status);

    /** Same contract as {@link DailyMealOptionRepository#recordActualPortions}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DailyComponentStock s SET " +
            "s.bufferRemaining = s.bufferRemaining + :actual - COALESCE(s.actualQuantity, 0), " +
            "s.actualQuantity = :actual, s.status = :ready " +
            "WHERE s.id = :id AND s.status <> :planned " +
            "AND s.bufferRemaining + :actual - COALESCE(s.actualQuantity, 0) >= 0")
    int recordActualQuantity(@Param("id") Long id, @Param("actual") int actual,
                             @Param("planned") DailyPlanItemStatus planned, @Param("ready") DailyPlanItemStatus ready);

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
