package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** @deprecated see {@link LegacyStockTake}. Frozen as-is (still hardcoded to Main Store, still a
 * one-step direct write) — kept only so historical callers/data keep working, not extended. */
@Deprecated
@Service
@AllArgsConstructor
public class LegacyStockTakeService {
    private static final String COUNTED_LOCATION = "Main Store";

    private final LegacyStockTakeRepository legacyStockTakeRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public LegacyStockTakeListResponseDto list(Long ingredientId, LocalDateTime from, LocalDateTime to) {
        List<LegacyStockTake> results;
        if (ingredientId != null && from != null && to != null) {
            results = legacyStockTakeRepository.findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(ingredientId, from, to);
        } else if (ingredientId != null) {
            results = legacyStockTakeRepository.findByIngredientIdOrderByCreatedAtDesc(ingredientId);
        } else if (from != null && to != null) {
            results = legacyStockTakeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
        } else {
            results = legacyStockTakeRepository.findAllByOrderByCreatedAtDesc();
        }
        return new LegacyStockTakeListResponseDto(results.stream().map(this::toListItemDto).toList());
    }

    private LegacyStockTakeListItemDto toListItemDto(LegacyStockTake stockTake) {
        var dto = new LegacyStockTakeListItemDto();
        dto.setId(stockTake.getId());
        dto.setIngredientId(stockTake.getIngredient().getId());
        dto.setIngredientName(stockTake.getIngredient().getName());
        dto.setCountedQuantity(stockTake.getCountedQuantity());
        dto.setExpectedQuantity(stockTake.getCountedQuantity().subtract(stockTake.getVariance()));
        dto.setVariance(stockTake.getVariance());
        dto.setNote(stockTake.getNote());
        dto.setRecordedBy(stockTake.getRecordedBy().getName());
        dto.setCreatedAt(stockTake.getCreatedAt());
        return dto;
    }

    @Transactional
    public LegacyStockTakeResponseDto create(LegacyCreateStockTakeRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var mainStore = locationRepository.findByNameIgnoreCase(COUNTED_LOCATION)
                .orElseThrow(() -> new IllegalStateException(COUNTED_LOCATION + " location not seeded."));
        var currentStock = ingredientStockMovementRepository
                .sumQuantityByIngredientIdAndLocationId(ingredient.getId(), mainStore.getId());
        var variance = request.getCountedQuantity().subtract(currentStock);

        var stockTake = new LegacyStockTake();
        stockTake.setIngredient(ingredient);
        stockTake.setCountedQuantity(request.getCountedQuantity());
        stockTake.setVariance(variance);
        stockTake.setNote(request.getNote());
        stockTake.setRecordedBy(authService.getCurrentUser());
        legacyStockTakeRepository.save(stockTake);

        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.STOCK_TAKE_ADJUSTMENT);
        movement.setQuantity(variance);
        movement.setSourceType(MovementSourceType.STOCK_TAKE);
        movement.setSourceId(stockTake.getId());
        movement.setLocation(mainStore);
        movement.setRecordedBy(stockTake.getRecordedBy());
        ingredientStockMovementRepository.save(movement);

        return new LegacyStockTakeResponseDto(stockTake.getId(), variance);
    }
}
