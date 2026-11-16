package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.InvalidAuthorizationTokenException;
import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.menu.AlwaysOnMenuService;
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
    /** O1: set to "true" on a 200 that replayed an earlier request with the same idempotency key. */
    public static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replay";

    private final OrderService orderService;
    private final OrderSubmissionService orderSubmissionService;
    private final AlwaysOnMenuService alwaysOnMenuService;

    @PostMapping
    public ResponseEntity<OrderDto> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            UriComponentsBuilder uriBuilder) {
        // Defensive: today's always-on options normally exist from the till's menu request. Done
        // here, outside the order's transaction, so it never holds two connections at once.
        alwaysOnMenuService.ensureAlwaysOnOptionsToday();
        var result = orderSubmissionService.submitOrder(request);
        var uri = uriBuilder.path("/orders/{id}").buildAndExpand(result.order().getId()).toUri();

        // O1: 201 when this call created the sale; 200 when it replayed an earlier one.
        return result.replayed()
                ? ResponseEntity.ok().location(uri).header(IDEMPOTENT_REPLAY_HEADER, "true").body(result.order())
                : ResponseEntity.created(uri).body(result.order());
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
        var result = orderSubmissionService.submitAdjustment(id, request);
        return result.replayed()
                ? ResponseEntity.ok().header(IDEMPOTENT_REPLAY_HEADER, "true").body(result.order())
                : ResponseEntity.status(HttpStatus.CREATED).body(result.order());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorDto> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage(), "IDEMPOTENCY_CONFLICT"));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Void> handleOrderNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(NoOpenShiftException.class)
    public ResponseEntity<ErrorDto> handleNoOpenShift(NoOpenShiftException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage(), "NO_OPEN_SHIFT"));
    }

    // Same 409 as NO_OPEN_SHIFT so status-based clients keep working; clients branch on code.
    @ExceptionHandler(TillInUseException.class)
    public ResponseEntity<ErrorDto> handleTillInUse(TillInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage(), "TILL_IN_USE"));
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
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorDto(ex.getMessage(), "AUTHORIZATION_INVALID"));
    }

    @ExceptionHandler(OrderRuleException.class)
    public ResponseEntity<ErrorDto> handleOrderRule(OrderRuleException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage(), ex.getCode()));
    }

    @ExceptionHandler({OrderAlreadyAdjustedException.class, ExtrasAlreadyAdjustedException.class})
    public ResponseEntity<ErrorDto> handleAdjustmentConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }
}
