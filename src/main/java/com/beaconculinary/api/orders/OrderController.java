package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.InvalidAuthorizationTokenException;
import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;
    private final OrderAdjustmentService orderAdjustmentService;

    @PostMapping
    public ResponseEntity<OrderDto> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            UriComponentsBuilder uriBuilder) {
        var order = orderService.createOrder(request);
        var uri = uriBuilder.path("/orders/{id}").buildAndExpand(order.getId()).toUri();

        return ResponseEntity.created(uri).body(order);
    }

    @GetMapping("/today")
    public List<OrderDto> getTodayOrders() {
        return orderService.getTodayOrders();
    }

    @GetMapping("/{id}")
    public OrderDto getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    @PostMapping("/{id}/mark-print-failed")
    public OrderDto markPrintFailed(@PathVariable Long id) {
        return orderService.markPrintFailed(id);
    }

    @PostMapping("/{id}/adjustments")
    public ResponseEntity<OrderDto> adjustOrder(
            @PathVariable Long id,
            @Valid @RequestBody CreateOrderAdjustmentRequest request) {
        var order = orderAdjustmentService.adjustOrder(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Void> handleOrderNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(NoOpenShiftException.class)
    public ResponseEntity<ErrorDto> handleNoOpenShift(NoOpenShiftException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorDto> handleInsufficientStock(InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InvalidOrderRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidOrderRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InvalidAuthorizationTokenException.class)
    public ResponseEntity<ErrorDto> handleInvalidAuthorizationToken(InvalidAuthorizationTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler({OrderAlreadyAdjustedException.class, ExtrasAlreadyAdjustedException.class})
    public ResponseEntity<ErrorDto> handleAdjustmentConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }
}
