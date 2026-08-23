package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/purchase-orders")
public class PurchaseOrderController {
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public List<PurchaseOrderDto> getAll() {
        return purchaseOrderService.getAll();
    }

    @GetMapping("/{id}")
    public PurchaseOrderDto getById(@PathVariable Long id) {
        return purchaseOrderService.getById(id);
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderDto> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderService.create(request));
    }

    @PutMapping("/{id}")
    public PurchaseOrderDto updateStatus(@PathVariable Long id, @Valid @RequestBody UpdatePurchaseOrderStatusRequest request) {
        return purchaseOrderService.updateStatus(id, request);
    }

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
