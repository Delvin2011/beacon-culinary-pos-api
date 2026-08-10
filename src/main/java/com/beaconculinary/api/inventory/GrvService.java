package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class GrvService {
    private final GrvRepository grvRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional
    public GrvDto create(CreateGrvRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var grv = new Grv();
        grv.setIngredient(ingredient);
        grv.setQuantity(request.getQuantity());
        grv.setCostPerUnit(request.getCostPerUnit());
        grv.setSupplierName(request.getSupplierName());
        grv.setNote(request.getNote());
        grv.setReceivedBy(authService.getCurrentUser());
        grvRepository.save(grv);

        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.RECEIVED);
        movement.setQuantity(request.getQuantity());
        movement.setCostPerUnit(request.getCostPerUnit());
        movement.setSourceType(MovementSourceType.GRV);
        movement.setSourceId(grv.getId());
        movement.setRecordedBy(grv.getReceivedBy());
        ingredientStockMovementRepository.save(movement);

        return inventoryMapper.toDto(grv);
    }

    @Transactional(readOnly = true)
    public List<GrvDto> list(Long ingredientId, LocalDateTime from, LocalDateTime to) {
        List<Grv> results;
        if (ingredientId != null && from != null && to != null) {
            results = grvRepository.findByIngredientIdAndReceivedAtBetweenOrderByReceivedAtDesc(ingredientId, from, to);
        } else if (ingredientId != null) {
            results = grvRepository.findByIngredientIdOrderByReceivedAtDesc(ingredientId);
        } else if (from != null && to != null) {
            results = grvRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to);
        } else {
            results = grvRepository.findAllByOrderByReceivedAtDesc();
        }
        return results.stream().map(inventoryMapper::toDto).toList();
    }
}
