package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class StockTakeService {
    private final StockTakeRepository stockTakeRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final AuthService authService;

    @Transactional
    public StockTakeResponseDto create(CreateStockTakeRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var currentStock = ingredientStockMovementRepository.sumQuantityByIngredientId(ingredient.getId());
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
        movement.setRecordedBy(stockTake.getRecordedBy());
        ingredientStockMovementRepository.save(movement);

        return new StockTakeResponseDto(stockTake.getId(), variance);
    }
}
