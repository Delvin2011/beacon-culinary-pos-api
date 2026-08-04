package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.orders.OrderNotFoundException;
import com.beaconculinary.api.orders.OrderSummaryDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/kitchen/orders")
public class KitchenController {
    private final KitchenService kitchenService;

    @GetMapping
    public List<OrderSummaryDto> getActiveOrders() {
        return kitchenService.getActiveOrders();
    }

    @PatchMapping("/{id}/status")
    public OrderSummaryDto updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateOrderStatusRequest request) {
        return kitchenService.updateStatus(id, request.getStatus());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return kitchenService.subscribe();
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Void> handleOrderNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorDto> handleInvalidTransition(InvalidStatusTransitionException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
