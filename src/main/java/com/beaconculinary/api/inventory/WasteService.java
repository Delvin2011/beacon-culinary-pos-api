package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class WasteService {
    private final WasteEntryRepository wasteEntryRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional
    public WasteEntryDto create(CreateWasteRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var wasteEntry = new WasteEntry();
        wasteEntry.setIngredient(ingredient);
        wasteEntry.setQuantity(request.getQuantity());
        wasteEntry.setReason(request.getReason());
        wasteEntry.setNote(request.getNote());
        wasteEntry.setRecordedBy(authService.getCurrentUser());
        wasteEntryRepository.save(wasteEntry);

        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.WASTED);
        movement.setQuantity(request.getQuantity().negate());
        movement.setSourceType(MovementSourceType.WASTE_ENTRY);
        movement.setSourceId(wasteEntry.getId());
        movement.setRecordedBy(wasteEntry.getRecordedBy());
        ingredientStockMovementRepository.save(movement);

        return inventoryMapper.toDto(wasteEntry);
    }
}
