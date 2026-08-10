package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface GrvRepository extends JpaRepository<Grv, Long> {
    List<Grv> findAllByOrderByReceivedAtDesc();

    List<Grv> findByIngredientIdOrderByReceivedAtDesc(Long ingredientId);

    List<Grv> findByReceivedAtBetweenOrderByReceivedAtDesc(LocalDateTime from, LocalDateTime to);

    List<Grv> findByIngredientIdAndReceivedAtBetweenOrderByReceivedAtDesc(Long ingredientId, LocalDateTime from, LocalDateTime to);
}
