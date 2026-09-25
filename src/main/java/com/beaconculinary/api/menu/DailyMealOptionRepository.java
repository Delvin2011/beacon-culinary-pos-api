package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyMealOptionRepository extends JpaRepository<DailyMealOption, Long> {
    List<DailyMealOption> findByOptionDateAndMealPeriodIdOrderById(LocalDate optionDate, Long mealPeriodId);

    /** With status PLANNED: the set an ingredient-requirements calculation sums over, excluding
     * rows already covered by a confirmation. With READY: what the POS may sell. */
    List<DailyMealOption> findByOptionDateAndMealPeriodIdAndStatusOrderById(
            LocalDate optionDate, Long mealPeriodId, DailyPlanItemStatus status);

    /**
     * Records (or corrects) the actual, moving the row to READY. remaining shifts by the same
     * delta as the actual, so sold is preserved; the WHERE guard refuses an actual below what's
     * already sold in the same statement as the write, so a sale landing concurrently can't slip
     * under it. Returns rows affected (0 or 1). SQL Server evaluates every SET expression against
     * the pre-update row, so the COALESCE reads the old actual.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DailyMealOption o SET " +
            "o.portionsRemaining = o.portionsRemaining + :actual - COALESCE(o.actualPortions, 0), " +
            "o.actualPortions = :actual, o.status = :ready " +
            "WHERE o.id = :id AND o.status <> :planned " +
            "AND o.portionsRemaining + :actual - COALESCE(o.actualPortions, 0) >= 0")
    int recordActualPortions(@Param("id") Long id, @Param("actual") int actual,
                             @Param("planned") DailyPlanItemStatus planned, @Param("ready") DailyPlanItemStatus ready);

    /**
     * Conditional decrement guarded by the WHERE clause — returns rows affected (0 or 1) so the
     * caller can detect an oversell attempt without a separate read-then-write race window.
     */
    @Modifying
    @Query("UPDATE DailyMealOption o SET o.portionsRemaining = o.portionsRemaining - :quantity " +
            "WHERE o.id = :id AND o.portionsRemaining >= :quantity")
    int decrementPortionsRemaining(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * Stage 2.6 — additive restoration on a VOID adjustment. No oversell risk here since we're
     * only ever increasing stock, so unlike the decrement there's no conditional WHERE guard.
     */
    @Modifying
    @Query("UPDATE DailyMealOption o SET o.portionsRemaining = o.portionsRemaining + :quantity WHERE o.id = :id")
    void incrementPortionsRemaining(@Param("id") Long id, @Param("quantity") int quantity);
}
