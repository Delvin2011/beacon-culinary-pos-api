package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stage 5.2.5 — submit/review flow for physical stock counts, location-selectable (Main Store or
 * Kitchen). A clerk (or {@code STOCK_ADMIN}/{@code ADMIN}) submits what they counted; a {@code
 * STOCK_ADMIN}/{@code ADMIN} reviews it as a binary credibility check — approve or reject, never
 * an edit. Only {@link #review} ever writes an {@link IngredientStockMovement}.
 */
@Service
@AllArgsConstructor
public class StockTakeService {
    private final StockTakeRepository stockTakeRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;
    private final Clock clock;

    @Transactional
    public StockTakeDto create(CreateStockTakeRequest request) {
        var location = resolveActiveLocation(request.getLocationId());

        var stockTake = new StockTake();
        stockTake.setLocation(location);
        stockTake.setSubmittedBy(authService.getCurrentUser());
        stockTake.setStatus(StockTakeStatus.SUBMITTED);

        for (var lineRequest : request.getLines()) {
            var ingredient = ingredientRepository.findById(lineRequest.getIngredientId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

            // Snapshotted now, never recalculated later — the honest record of what was true at
            // the moment of the physical count.
            var expectedQuantity = ingredientStockMovementRepository
                    .sumQuantityByIngredientIdAndLocationId(ingredient.getId(), location.getId());
            var unitCost = latestCost(ingredient.getId());
            var varianceQuantity = lineRequest.getActualQuantity().subtract(expectedQuantity);

            var line = new StockTakeLine();
            line.setStockTake(stockTake);
            line.setIngredient(ingredient);
            line.setExpectedQuantity(expectedQuantity);
            line.setActualQuantity(lineRequest.getActualQuantity());
            line.setUnitCost(unitCost);
            line.setVarianceQuantity(varianceQuantity);
            line.setVarianceValue(varianceQuantity.multiply(unitCost));
            stockTake.getLines().add(line);
        }

        stockTakeRepository.save(stockTake);
        return inventoryMapper.toDto(stockTake);
    }

    @Transactional(readOnly = true)
    public List<StockTakeDto> list(StockTakeStatus status, Long locationId) {
        return stockTakeRepository.search(status, locationId, ownSubmissionsFilterFor(authService.getCurrentUser()))
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public StockTakeDto getById(Long id) {
        return toDto(loadForCaller(id));
    }

    @Transactional
    public StockTakeDto review(Long id, ReviewStockTakeRequest request) {
        var stockTake = stockTakeRepository.findWithLinesById(id).orElseThrow(StockTakeNotFoundException::new);
        if (stockTake.getStatus() != StockTakeStatus.SUBMITTED) {
            throw new StockTakeAlreadyReviewedException();
        }

        var reviewedBy = authService.getCurrentUser();

        if (request.getDecision() == StockTakeDecision.APPROVE) {
            for (var line : stockTake.getLines()) {
                // Recomputed against CURRENT stock at approval time, not the stored
                // varianceQuantity — if activity happened between submission and review, only
                // this guarantees the ledger actually lands on actualQuantity.
                var currentStock = ingredientStockMovementRepository
                        .sumQuantityByIngredientIdAndLocationId(line.getIngredient().getId(), stockTake.getLocation().getId());
                var appliedAdjustmentQuantity = line.getActualQuantity().subtract(currentStock);
                line.setAppliedAdjustmentQuantity(appliedAdjustmentQuantity);

                var movement = new IngredientStockMovement();
                movement.setIngredient(line.getIngredient());
                movement.setMovementType(MovementType.STOCK_TAKE_ADJUSTMENT);
                movement.setQuantity(appliedAdjustmentQuantity);
                movement.setSourceType(MovementSourceType.STOCK_TAKE);
                movement.setSourceId(line.getId());
                movement.setLocation(stockTake.getLocation());
                movement.setRecordedBy(reviewedBy);
                ingredientStockMovementRepository.save(movement);
            }
            stockTake.setStatus(StockTakeStatus.APPROVED);
        } else {
            stockTake.setStatus(StockTakeStatus.REJECTED);
        }

        stockTake.setReviewedBy(reviewedBy);
        stockTake.setReviewedAt(LocalDateTime.now(clock));
        stockTake.setNote(request.getNote());
        stockTakeRepository.save(stockTake);

        return inventoryMapper.toDto(stockTake);
    }

    private BigDecimal latestCost(Long ingredientId) {
        return ingredientStockMovementRepository
                .findFirstByIngredientIdAndMovementTypeAndCostPerUnitIsNotNullOrderByCreatedAtDesc(ingredientId, MovementType.RECEIVED)
                .map(IngredientStockMovement::getCostPerUnit)
                .orElse(BigDecimal.ZERO);
    }

    private StockTake loadForCaller(Long id) {
        var stockTake = stockTakeRepository.findWithLinesById(id).orElseThrow(StockTakeNotFoundException::new);
        var currentUser = authService.getCurrentUser();
        if (currentUser.getRole() == Role.STOCK_CLERK && !stockTake.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Cannot access another user's stock take.");
        }
        return stockTake;
    }

    private Long ownSubmissionsFilterFor(User currentUser) {
        return currentUser.getRole() == Role.STOCK_CLERK ? currentUser.getId() : null;
    }

    private Location resolveActiveLocation(Long locationId) {
        if (locationId == null) {
            throw new InvalidInventoryRequestException("locationId is required.");
        }
        var location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InvalidInventoryRequestException("locationId does not exist."));
        if (!location.isActive()) {
            throw new InvalidInventoryRequestException("locationId does not reference an active location.");
        }
        return location;
    }

    private StockTakeDto toDto(StockTake stockTake) {
        return inventoryMapper.toDto(stockTake);
    }
}
