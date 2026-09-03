package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/grv")
public class GrvController {
    private final GrvService grvService;

    @GetMapping
    public List<GrvDto> list(
            @RequestParam(required = false) Long ingredientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long purchaseOrderId) {
        return grvService.list(ingredientId, from, to, purchaseOrderId);
    }

    @GetMapping("/{id}")
    public GrvDto getById(@PathVariable Long id) {
        return grvService.getById(id);
    }

    @PostMapping
    public ResponseEntity<GrvDto> create(@Valid @RequestBody CreateGrvRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(grvService.create(request));
    }

    /** Same-day correction of a data-entry mistake — rejected with 409 once the GRV's received
     * date (in the business's local timezone) is no longer today. */
    @PatchMapping("/{id}")
    public GrvDto update(@PathVariable Long id, @Valid @RequestBody UpdateGrvRequest request) {
        return grvService.update(id, request);
    }

    /** CSV upload with Invoice Number, Ingredient Name, Quantity, Cost Per Unit, Supplier Name,
     * and an optional Note column. Each row must reference an ingredient that already exists
     * (create it via /admin/ingredients/bulk-import first) — every row always creates its own
     * new one-line GRV, under its own invoice number. */
    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkGrvImportResultDto bulkImport(@RequestParam("file") MultipartFile file) {
        return grvService.bulkImport(file);
    }

    @ExceptionHandler(GrvNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidInventoryRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidInventoryRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(GrvEditWindowClosedException.class)
    public ResponseEntity<ErrorDto> handleEditWindowClosed(GrvEditWindowClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(BulkImportException.class)
    public ResponseEntity<Map<String, List<String>>> handleBulkImportError(BulkImportException ex) {
        return ResponseEntity.badRequest().body(Map.of("errors", ex.getErrors()));
    }
}
