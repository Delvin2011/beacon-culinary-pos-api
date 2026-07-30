package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyMealOptionRepository extends JpaRepository<DailyMealOption, Long> {
    List<DailyMealOption> findByOptionDateAndMealPeriodId(LocalDate optionDate, Long mealPeriodId);

    /**
     * Conditional decrement guarded by the WHERE clause — returns rows affected (0 or 1) so the
     * caller can detect an oversell attempt without a separate read-then-write race window.
     */
    @Modifying
    @Query("UPDATE DailyMealOption o SET o.portionsRemaining = o.portionsRemaining - :quantity " +
            "WHERE o.id = :id AND o.portionsRemaining >= :quantity")
    int decrementPortionsRemaining(@Param("id") Long id, @Param("quantity") int quantity);
}
