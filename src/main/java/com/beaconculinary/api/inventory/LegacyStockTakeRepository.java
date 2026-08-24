package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** @deprecated see {@link LegacyStockTake}. */
@Deprecated
public interface LegacyStockTakeRepository extends JpaRepository<LegacyStockTake, Long> {
    List<LegacyStockTake> findAllByOrderByCreatedAtDesc();

    List<LegacyStockTake> findByIngredientIdOrderByCreatedAtDesc(Long ingredientId);

    List<LegacyStockTake> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    List<LegacyStockTake> findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long ingredientId, LocalDateTime from, LocalDateTime to);
}
