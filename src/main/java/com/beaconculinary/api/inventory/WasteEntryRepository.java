package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface WasteEntryRepository extends JpaRepository<WasteEntry, Long> {
    List<WasteEntry> findAllByOrderByCreatedAtDesc();

    List<WasteEntry> findByIngredientIdOrderByCreatedAtDesc(Long ingredientId);

    List<WasteEntry> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    List<WasteEntry> findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long ingredientId, LocalDateTime from, LocalDateTime to);
}
