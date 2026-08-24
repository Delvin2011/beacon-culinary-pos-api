package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class PurchaseOrderService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final IngredientRepository ingredientRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getAll() {
        return inventoryMapper.toPurchaseOrderDtoList(purchaseOrderRepository.findAllWithLinesByOrderByCreatedAtDesc());
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getById(Long id) {
        var purchaseOrder = purchaseOrderRepository.findWithLinesById(id).orElseThrow(PurchaseOrderNotFoundException::new);
        return inventoryMapper.toDto(purchaseOrder);
    }

    @Transactional
    public PurchaseOrderDto create(CreatePurchaseOrderRequest request) {
        var ingredientIds = request.getLines().stream().map(PurchaseOrderLineRequest::getIngredientId).toList();
        var ingredients = ingredientRepository.findAllByIdIn(ingredientIds);
        if (ingredients.size() != new HashSet<>(ingredientIds).size()) {
            throw new InvalidInventoryRequestException("One or more ingredientIds do not exist.");
        }
        var ingredientsById = ingredients.stream().collect(Collectors.toMap(Ingredient::getId, i -> i));

        var purchaseOrder = new PurchaseOrder();
        purchaseOrder.setSupplierName(request.getSupplierName());
        purchaseOrder.setCreatedBy(authService.getCurrentUser());

        for (var lineRequest : request.getLines()) {
            var line = new PurchaseOrderLine();
            line.setPurchaseOrder(purchaseOrder);
            line.setIngredient(ingredientsById.get(lineRequest.getIngredientId()));
            line.setQuantity(lineRequest.getQuantity());
            line.setNote(lineRequest.getNote());
            purchaseOrder.getLines().add(line);
        }

        purchaseOrderRepository.save(purchaseOrder);
        return inventoryMapper.toDto(purchaseOrder);
    }

    @Transactional
    public PurchaseOrderDto updateStatus(Long id, UpdatePurchaseOrderStatusRequest request) {
        var purchaseOrder = purchaseOrderRepository.findWithLinesById(id).orElseThrow(PurchaseOrderNotFoundException::new);
        purchaseOrder.setStatus(request.getStatus());
        purchaseOrderRepository.save(purchaseOrder);
        return inventoryMapper.toDto(purchaseOrder);
    }
}
