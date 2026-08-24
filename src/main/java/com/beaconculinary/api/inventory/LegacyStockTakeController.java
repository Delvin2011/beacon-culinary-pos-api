package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** @deprecated Stage 5.2.5 — this one-step, Main-Store-only, {@code STOCK_ADMIN}-only endpoint is
 * superseded by {@code POST /stock-takes} + {@code POST /stock-takes/{id}/review} (location-
 * selectable, {@code STOCK_CLERK} can submit). Left running only for historical data/backward
 * compatibility — no new code path should call it. */
@Deprecated
@AllArgsConstructor
@RestController
@RequestMapping("/admin/stock-takes")
public class LegacyStockTakeController {
    private final LegacyStockTakeService legacyStockTakeService;

    @GetMapping
    public LegacyStockTakeListResponseDto list(
            @RequestParam(required = false) Long ingredientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return legacyStockTakeService.list(ingredientId, from, to);
    }

    @PostMapping
    public ResponseEntity<LegacyStockTakeResponseDto> create(@Valid @RequestBody LegacyCreateStockTakeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(legacyStockTakeService.create(request));
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
