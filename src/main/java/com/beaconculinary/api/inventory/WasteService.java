package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class WasteService {
    private final WasteEntryRepository wasteEntryRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional(readOnly = true)
    public WasteListResponseDto list(Long ingredientId, LocalDateTime from, LocalDateTime to) {
        List<WasteEntry> results;
        if (ingredientId != null && from != null && to != null) {
            results = wasteEntryRepository.findByIngredientIdAndCreatedAtBetweenOrderByCreatedAtDesc(ingredientId, from, to);
        } else if (ingredientId != null) {
            results = wasteEntryRepository.findByIngredientIdOrderByCreatedAtDesc(ingredientId);
        } else if (from != null && to != null) {
            results = wasteEntryRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
        } else {
            results = wasteEntryRepository.findAllByOrderByCreatedAtDesc();
        }
        return new WasteListResponseDto(results.stream().map(this::toListItemDto).toList());
    }

    private WasteEntryListItemDto toListItemDto(WasteEntry wasteEntry) {
        var dto = new WasteEntryListItemDto();
        dto.setId(wasteEntry.getId());
        dto.setIngredientId(wasteEntry.getIngredient().getId());
        dto.setIngredientName(wasteEntry.getIngredient().getName());
        dto.setLocationId(wasteEntry.getLocation().getId());
        dto.setLocationName(wasteEntry.getLocation().getName());
        dto.setQuantity(wasteEntry.getQuantity());
        dto.setReason(wasteEntry.getReason());
        dto.setNote(wasteEntry.getNote());
        dto.setRecordedBy(wasteEntry.getRecordedBy().getName());
        dto.setCreatedAt(wasteEntry.getCreatedAt());
        return dto;
    }

    @Transactional
    public WasteEntryDto create(CreateWasteRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));
        var location = resolveActiveLocation(request.getLocationId());

        var wasteEntry = new WasteEntry();
        wasteEntry.setIngredient(ingredient);
        wasteEntry.setLocation(location);
        wasteEntry.setQuantity(request.getQuantity());
        wasteEntry.setReason(request.getReason());
        wasteEntry.setNote(request.getNote());
        wasteEntry.setRecordedBy(authService.getCurrentUser());
        wasteEntryRepository.save(wasteEntry);

        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.WASTED);
        movement.setQuantity(request.getQuantity().negate());
        movement.setSourceType(MovementSourceType.DIRECT_WASTE);
        movement.setSourceId(wasteEntry.getId());
        movement.setLocation(location);
        movement.setRecordedBy(wasteEntry.getRecordedBy());
        ingredientStockMovementRepository.save(movement);

        return inventoryMapper.toDto(wasteEntry);
    }

    private Location resolveActiveLocation(Long locationId) {
        var location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InvalidInventoryRequestException("locationId does not exist."));
        if (!location.isActive()) {
            throw new InvalidInventoryRequestException("locationId does not reference an active location.");
        }
        return location;
    }
}
