package com.beaconculinary.api.bar;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.orders.OrderNotFoundException;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.OrderSummaryDto;
import com.beaconculinary.api.orders.StationTransitionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** BARISTA and ADMIN (BAR_DISPLAY until U2): BarSecurityRules. */
@AllArgsConstructor
@RestController
@RequestMapping("/bar/orders")
public class BarController {
    private final BarService barService;

    @Data
    public static class UpdateBarStatusRequest {
        @NotNull(message = "status is required")
        private OrderStatus status;
    }

    @GetMapping
    public List<OrderSummaryDto> getActiveOrders() {
        return barService.getActiveOrders();
    }

    @PostMapping("/{id}/status")
    public OrderSummaryDto updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateBarStatusRequest request) {
        return barService.updateStatus(id, request.getStatus());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return barService.subscribe();
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Void> handleOrderNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(StationTransitionException.class)
    public ResponseEntity<ErrorDto> handleInvalidTransition(StationTransitionException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
