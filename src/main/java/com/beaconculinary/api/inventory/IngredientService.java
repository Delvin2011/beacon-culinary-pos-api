package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@AllArgsConstructor
public class IngredientService {
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final InventoryMapper inventoryMapper;

    @Transactional(readOnly = true)
    public List<IngredientDto> getAll() {
        return ingredientRepository.findAll().stream().map(inventoryMapper::toDto).toList();
    }

    @Transactional
    public IngredientDto create(CreateIngredientRequest request) {
        var ingredient = new Ingredient();
        ingredient.setName(request.getName());
        ingredient.setUnit(request.getUnit());
        ingredient.setCountSheetCategory(request.getCountSheetCategory());
        ingredientRepository.save(ingredient);
        return inventoryMapper.toDto(ingredient);
    }

    @Transactional
    public IngredientDto update(Long id, UpdateIngredientRequest request) {
        var ingredient = ingredientRepository.findById(id).orElseThrow(IngredientNotFoundException::new);
        ingredient.setName(request.getName());
        ingredient.setUnit(request.getUnit());
        ingredient.setCountSheetCategory(request.getCountSheetCategory());
        ingredient.setActive(request.isActive());
        ingredientRepository.save(ingredient);
        return inventoryMapper.toDto(ingredient);
    }

    @Transactional(readOnly = true)
    public IngredientStockDto getStock(Long id) {
        if (!ingredientRepository.existsById(id)) {
            throw new IngredientNotFoundException();
        }
        BigDecimal currentStock = ingredientStockMovementRepository.sumQuantityByIngredientId(id);
        var lastMovementAt = ingredientStockMovementRepository.findLastMovementAtByIngredientId(id);
        return new IngredientStockDto(currentStock, lastMovementAt);
    }
}
