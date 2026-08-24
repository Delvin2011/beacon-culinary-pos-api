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
@RequestMapping("/stock-takes")
public class StockTakeController {
    private final StockTakeService stockTakeService;

    @GetMapping
    public List<StockTakeDto> list(
            @RequestParam(required = false) StockTakeStatus status,
            @RequestParam(required = false) Long locationId) {
        return stockTakeService.list(status, locationId);
    }

    @GetMapping("/{id}")
    public StockTakeDto getById(@PathVariable Long id) {
        return stockTakeService.getById(id);
    }

    @PostMapping
    public ResponseEntity<StockTakeDto> create(@Valid @RequestBody CreateStockTakeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockTakeService.create(request));
    }

    @PostMapping("/{id}/review")
    public StockTakeDto review(@PathVariable Long id, @Valid @RequestBody ReviewStockTakeRequest request) {
        return stockTakeService.review(id, request);
    }

    @ExceptionHandler(StockTakeNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(StockTakeAlreadyReviewedException.class)
    public ResponseEntity<ErrorDto> handleAlreadyReviewed(StockTakeAlreadyReviewedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
