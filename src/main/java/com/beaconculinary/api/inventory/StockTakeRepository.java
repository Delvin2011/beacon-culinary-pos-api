package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StockTakeRepository extends JpaRepository<StockTake, Long> {
    List<StockTake> findAllByOrderByCreatedAtDesc();

    List<StockTake> findByIngredientIdOrderByCreatedAtDesc(Long ingredientId);

    List<StockTake> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    List<StockTake> findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long ingredientId, LocalDateTime from, LocalDateTime to);
}
