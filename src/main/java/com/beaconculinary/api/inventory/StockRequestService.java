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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stage 5 Revision — request-then-authorize for ISSUE and WASTE stock actions. A {@code
 * STOCK_CLERK} (or {@code STOCK_ADMIN}/{@code ADMIN} acting directly) submits a request; a
 * {@code STOCK_ADMIN}/{@code ADMIN} authorizes it via {@link #action}, which is the only place
 * that actually writes {@link IngredientStockMovement} rows for this flow.
 *
 * <p>Adapted to this codebase's already-built Location model (Stage 5.2.1), which this spec
 * predates: an ISSUE always moves stock Main Store -&gt; Kitchen (the only two locations that
 * exist), writing the paired {@code ISSUED}/{@code RECEIVED} movement Stage 5.2.1 Section 3
 * described as a later follow-up once this request/authorize flow existed. A WASTE-type
 * request's approved amount is written at Main Store, same as a direct waste entry.
 *
 * <p>Stage 5.2.3 — a third {@code requestType}, {@code ORDER}, completes the set: approving one
 * produces a {@link PurchaseOrder} instead of any stock movement — ordering is not receiving.
 *
 * <p>Stage 5.2.4 — a WASTE request now carries its own location (header-level, chosen at
 * submission), instead of always defaulting to Main Store. ISSUE stays fixed Main Store -&gt;
 * Kitchen; ORDER stays location-agnostic.
 */
@Service
@AllArgsConstructor
public class StockRequestService {
    private static final String ISSUE_SOURCE_LOCATION = "Main Store";
    private static final String ISSUE_DESTINATION_LOCATION = "Kitchen";

    private final StockRequestRepository stockRequestRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;
    private final Clock clock;

    @Transactional
    public StockRequestDto create(CreateStockRequestRequest request) {
        var stockRequest = new StockRequest();
        stockRequest.setRequestType(request.getRequestType());
        stockRequest.setSource(StockRequestSource.MANUAL);
        stockRequest.setRequestedBy(authService.getCurrentUser());
        stockRequest.setStatus(StockRequestStatus.REQUESTED);

        if (request.getRequestType() == StockRequestType.WASTE) {
            stockRequest.setLocation(resolveActiveLocation(request.getLocationId()));
        }

        for (var lineRequest : request.getLines()) {
            var ingredient = ingredientRepository.findById(lineRequest.getIngredientId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

            var line = new StockRequestLine();
            line.setStockRequest(stockRequest);
            line.setIngredient(ingredient);
            line.setRequestedQuantity(lineRequest.getQuantity());
            // WASTE: "reason for wasting"; ORDER: "reason for ordering". Ignored for ISSUE.
            line.setReason(lineRequest.getReason());
            stockRequest.getLines().add(line);
        }

        stockRequestRepository.save(stockRequest);
        return toDto(stockRequest);
    }

    @Transactional(readOnly = true)
    public List<StockRequestDto> list(StockRequestStatus status, StockRequestType type) {
        return stockRequestRepository.search(status, type, ownRequestsFilterFor(authService.getCurrentUser()))
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public StockRequestDto getById(Long id) {
        return toDto(loadForCaller(id));
    }

    @Transactional
    public StockRequestDto action(Long id, ActionStockRequestRequest request) {
        var stockRequest = stockRequestRepository.findWithLinesById(id).orElseThrow(StockRequestNotFoundException::new);

        if (stockRequest.getRequestType() == StockRequestType.ORDER) {
            return actionOrder(stockRequest, request);
        }

        if (stockRequest.getStatus() == StockRequestStatus.ACTIONED || stockRequest.getStatus() == StockRequestStatus.REJECTED) {
            throw new StockRequestAlreadyFinalizedException();
        }

        Map<Long, BigDecimal> actionedQuantityByIngredientId = request.getLines().stream()
                .collect(Collectors.toMap(ActionStockRequestLineRequest::getIngredientId, ActionStockRequestLineRequest::getActionedQuantity));

        var actionedBy = authService.getCurrentUser();
        var isIssue = stockRequest.getRequestType() == StockRequestType.ISSUE;
        var mainStore = isIssue ? location(ISSUE_SOURCE_LOCATION) : null;
        var kitchen = isIssue ? location(ISSUE_DESTINATION_LOCATION) : null;
        // WASTE: fixed at submission (create()) — approving can adjust quantities, never location.
        var wasteLocation = isIssue ? null : stockRequest.getLocation();

        for (var line : stockRequest.getLines()) {
            // Idempotency for a follow-up call on a PARTIALLY_ACTIONED request: a line already
            // actioned by a previous call is left untouched, never re-processed/double-written.
            if (line.getActionedQuantity() != null) {
                continue;
            }

            var actionedQuantity = actionedQuantityByIngredientId.get(line.getIngredient().getId());
            if (actionedQuantity == null) {
                throw new InvalidInventoryRequestException(
                        "actionedQuantity missing for ingredientId " + line.getIngredient().getId() + ".");
            }

            if (stockRequest.getRequestType() == StockRequestType.ISSUE) {
                var availableStock = ingredientStockMovementRepository
                        .sumQuantityByIngredientIdAndLocationId(line.getIngredient().getId(), mainStore.getId());
                if (actionedQuantity.compareTo(availableStock) > 0) {
                    throw new InvalidInventoryRequestException("actionedQuantity for ingredientId "
                            + line.getIngredient().getId() + " exceeds available Main Store stock.");
                }
            } else {
                if (actionedQuantity.compareTo(line.getRequestedQuantity()) > 0) {
                    throw new InvalidInventoryRequestException("actionedQuantity for ingredientId "
                            + line.getIngredient().getId() + " exceeds requested quantity.");
                }
            }

            line.setActionedQuantity(actionedQuantity);
            if (actionedQuantity.compareTo(BigDecimal.ZERO) > 0) {
                writeMovementsForLine(stockRequest, line, actionedQuantity, mainStore, kitchen, wasteLocation, actionedBy);
            }
        }

        stockRequest.setStatus(determineStatus(stockRequest.getLines()));
        stockRequest.setActionedBy(actionedBy);
        stockRequest.setActionedAt(LocalDateTime.now(clock));
        stockRequestRepository.save(stockRequest);

        return toDto(stockRequest);
    }

    // ORDER never partially approves — it's the whole request, forming one PO, or nothing (no
    // endpoint here rejects one outright; that's simply not exercised by this call). No cap
    // against any stock figure: an order is a forward-looking decision, not a movement.
    private StockRequestDto actionOrder(StockRequest stockRequest, ActionStockRequestRequest request) {
        if (stockRequest.getStatus() != StockRequestStatus.REQUESTED) {
            throw new StockRequestAlreadyFinalizedException();
        }
        if (request.getSupplierName() == null || request.getSupplierName().isBlank()) {
            throw new InvalidInventoryRequestException("supplierName is required to action an ORDER request.");
        }

        Map<Long, BigDecimal> actionedQuantityByIngredientId = request.getLines().stream()
                .collect(Collectors.toMap(ActionStockRequestLineRequest::getIngredientId, ActionStockRequestLineRequest::getActionedQuantity));

        var actionedBy = authService.getCurrentUser();

        var purchaseOrder = new PurchaseOrder();
        purchaseOrder.setSupplierName(request.getSupplierName());
        purchaseOrder.setStatus(PurchaseOrderStatus.SUBMITTED);
        purchaseOrder.setStockRequest(stockRequest);
        purchaseOrder.setCreatedBy(actionedBy);

        for (var line : stockRequest.getLines()) {
            var actionedQuantity = actionedQuantityByIngredientId.get(line.getIngredient().getId());
            if (actionedQuantity == null) {
                throw new InvalidInventoryRequestException(
                        "actionedQuantity missing for ingredientId " + line.getIngredient().getId() + ".");
            }
            if (actionedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidInventoryRequestException("actionedQuantity for ingredientId "
                        + line.getIngredient().getId() + " must be positive — an ORDER request has no partial approval.");
            }

            line.setActionedQuantity(actionedQuantity);

            var poLine = new PurchaseOrderLine();
            poLine.setPurchaseOrder(purchaseOrder);
            poLine.setIngredient(line.getIngredient());
            poLine.setQuantity(actionedQuantity);
            purchaseOrder.getLines().add(poLine);
        }

        purchaseOrderRepository.save(purchaseOrder);

        stockRequest.setStatus(StockRequestStatus.ACTIONED);
        stockRequest.setActionedBy(actionedBy);
        stockRequest.setActionedAt(LocalDateTime.now(clock));
        stockRequestRepository.save(stockRequest);

        var dto = inventoryMapper.toDto(stockRequest);
        dto.setPurchaseOrderId(purchaseOrder.getId());
        return dto;
    }

    // ISSUE moves stock Main Store -> Kitchen (the only two locations this system has); WASTE
    // writes off the approved amount at the request's own location, chosen at submission.
    private void writeMovementsForLine(StockRequest stockRequest, StockRequestLine line, BigDecimal actionedQuantity,
                                        Location mainStore, Location kitchen, Location wasteLocation, User actionedBy) {
        if (stockRequest.getRequestType() == StockRequestType.ISSUE) {
            var issued = new IngredientStockMovement();
            issued.setIngredient(line.getIngredient());
            issued.setMovementType(MovementType.ISSUED);
            issued.setQuantity(actionedQuantity.negate());
            issued.setSourceType(MovementSourceType.STOCK_REQUEST);
            issued.setSourceId(stockRequest.getId());
            issued.setLocation(mainStore);
            issued.setRecordedBy(actionedBy);
            ingredientStockMovementRepository.save(issued);

            var received = new IngredientStockMovement();
            received.setIngredient(line.getIngredient());
            received.setMovementType(MovementType.RECEIVED);
            received.setQuantity(actionedQuantity);
            received.setSourceType(MovementSourceType.STOCK_REQUEST);
            received.setSourceId(stockRequest.getId());
            received.setLocation(kitchen);
            received.setRecordedBy(actionedBy);
            ingredientStockMovementRepository.save(received);
        } else {
            var wasted = new IngredientStockMovement();
            wasted.setIngredient(line.getIngredient());
            wasted.setMovementType(MovementType.WASTED);
            wasted.setQuantity(actionedQuantity.negate());
            wasted.setSourceType(MovementSourceType.STOCK_REQUEST);
            wasted.setSourceId(stockRequest.getId());
            wasted.setLocation(wasteLocation);
            wasted.setRecordedBy(actionedBy);
            ingredientStockMovementRepository.save(wasted);
        }
    }

    private StockRequestStatus determineStatus(List<StockRequestLine> lines) {
        boolean allFull = lines.stream().allMatch(
                l -> l.getActionedQuantity() != null && l.getActionedQuantity().compareTo(l.getRequestedQuantity()) == 0);
        if (allFull) {
            return StockRequestStatus.ACTIONED;
        }
        boolean allZero = lines.stream().allMatch(
                l -> l.getActionedQuantity() != null && l.getActionedQuantity().compareTo(BigDecimal.ZERO) == 0);
        return allZero ? StockRequestStatus.REJECTED : StockRequestStatus.PARTIALLY_ACTIONED;
    }

    private StockRequest loadForCaller(Long id) {
        var stockRequest = stockRequestRepository.findWithLinesById(id).orElseThrow(StockRequestNotFoundException::new);
        var currentUser = authService.getCurrentUser();
        if (currentUser.getRole() == Role.STOCK_CLERK && !stockRequest.getRequestedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Cannot access another user's stock request.");
        }
        return stockRequest;
    }

    private Long ownRequestsFilterFor(User currentUser) {
        return currentUser.getRole() == Role.STOCK_CLERK ? currentUser.getId() : null;
    }

    private Location location(String name) {
        return locationRepository.findByNameIgnoreCase(name)
                .orElseThrow(() -> new IllegalStateException(name + " location not seeded."));
    }

    private Location resolveActiveLocation(Long locationId) {
        if (locationId == null) {
            throw new InvalidInventoryRequestException("locationId is required for WASTE requests.");
        }
        var location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InvalidInventoryRequestException("locationId does not exist."));
        if (!location.isActive()) {
            throw new InvalidInventoryRequestException("locationId does not reference an active location.");
        }
        return location;
    }

    // Stage 5.2.3 — ORDER-type requests carry the id of whatever PO they were approved into, so
    // the frontend can link straight to it from any read (create/list/getById), not just the
    // action response.
    private StockRequestDto toDto(StockRequest stockRequest) {
        var dto = inventoryMapper.toDto(stockRequest);
        if (stockRequest.getRequestType() == StockRequestType.ORDER) {
            purchaseOrderRepository.findByStockRequestId(stockRequest.getId())
                    .ifPresent(po -> dto.setPurchaseOrderId(po.getId()));
        }
        return dto;
    }
}
