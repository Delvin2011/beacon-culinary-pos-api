package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class StockTakeService {
    // Stage 5.2.1: Stock Take has no location-selection UI yet — a physical count is implicitly
    // a Main Store count until a later stage adds real location awareness here.
    private static final String COUNTED_LOCATION = "Main Store";

    private final StockTakeRepository stockTakeRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public StockTakeListResponseDto list(Long ingredientId, LocalDateTime from, LocalDateTime to) {
        List<StockTake> results;
        if (ingredientId != null && from != null && to != null) {
            results = stockTakeRepository.findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(ingredientId, from, to);
        } else if (ingredientId != null) {
            results = stockTakeRepository.findByIngredientIdOrderByCreatedAtDesc(ingredientId);
        } else if (from != null && to != null) {
            results = stockTakeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
        } else {
            results = stockTakeRepository.findAllByOrderByCreatedAtDesc();
        }
        return new StockTakeListResponseDto(results.stream().map(this::toListItemDto).toList());
    }

    // expectedQuantity/variance are read back exactly as computed at creation time (see create()
    // below), never recalculated against current stock — a later movement on the same ingredient
    // must not change what a past stock take reported.
    private StockTakeListItemDto toListItemDto(StockTake stockTake) {
        var dto = new StockTakeListItemDto();
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
    public StockTakeResponseDto create(CreateStockTakeRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var mainStore = locationRepository.findByNameIgnoreCase(COUNTED_LOCATION)
                .orElseThrow(() -> new IllegalStateException(COUNTED_LOCATION + " location not seeded."));
        var currentStock = ingredientStockMovementRepository
                .sumQuantityByIngredientIdAndLocationId(ingredient.getId(), mainStore.getId());
        var variance = request.getCountedQuantity().subtract(currentStock);

        var stockTake = new StockTake();
        stockTake.setIngredient(ingredient);
        stockTake.setCountedQuantity(request.getCountedQuantity());
        stockTake.setVariance(variance);
        stockTake.setNote(request.getNote());
        stockTake.setRecordedBy(authService.getCurrentUser());
        stockTakeRepository.save(stockTake);

        // Reconciles the derived ledger total to match the physical count exactly, regardless
        // of which direction the variance runs.
        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.STOCK_TAKE_ADJUSTMENT);
        movement.setQuantity(variance);
        movement.setSourceType(MovementSourceType.STOCK_TAKE);
        movement.setSourceId(stockTake.getId());
        movement.setLocation(mainStore);
        movement.setRecordedBy(stockTake.getRecordedBy());
        ingredientStockMovementRepository.save(movement);

        return new StockTakeResponseDto(stockTake.getId(), variance);
    }
}
