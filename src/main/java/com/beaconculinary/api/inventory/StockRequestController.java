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
