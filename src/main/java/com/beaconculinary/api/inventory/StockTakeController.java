package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/stock-takes")
public class StockTakeController {
    private final StockTakeService stockTakeService;

    @PostMapping
    public ResponseEntity<StockTakeResponseDto> create(@Valid @RequestBody CreateStockTakeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockTakeService.create(request));
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
