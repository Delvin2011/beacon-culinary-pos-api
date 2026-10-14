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
@RequestMapping("/stock-requests")
public class StockRequestController {
    private final StockRequestService stockRequestService;

    @GetMapping
    public List<StockRequestDto> list(
            @RequestParam(required = false) StockRequestStatus status,
            @RequestParam(required = false) StockRequestType type) {
        return stockRequestService.list(status, type);
    }

    @GetMapping("/{id}")
    public StockRequestDto getById(@PathVariable Long id) {
        return stockRequestService.getById(id);
    }

    @PostMapping
    public ResponseEntity<StockRequestDto> create(@Valid @RequestBody CreateStockRequestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockRequestService.create(request));
    }

    @PostMapping("/{id}/action")
    public StockRequestDto action(@PathVariable Long id, @Valid @RequestBody ActionStockRequestRequest request) {
        return stockRequestService.action(id, request);
    }

    /** Edits the caller's own request while still REQUESTED (403 for anyone else's, 409 once actioned). */
    @PutMapping("/{id}")
    public StockRequestDto update(@PathVariable Long id, @Valid @RequestBody CreateStockRequestRequest request) {
        return stockRequestService.update(id, request);
    }

    /** Cancels the caller's own request while still REQUESTED. */
    @PostMapping("/{id}/cancel")
    public StockRequestDto cancel(@PathVariable Long id) {
        return stockRequestService.cancel(id);
    }

    @ExceptionHandler(StockItemNotPendingException.class)
    public ResponseEntity<ErrorDto> handleNotPending(StockItemNotPendingException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(StockRequestNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(StockRequestAlreadyFinalizedException.class)
    public ResponseEntity<ErrorDto> handleAlreadyFinalized(StockRequestAlreadyFinalizedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
