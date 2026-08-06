package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.InvalidAuthorizationTokenException;
import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@AllArgsConstructor
@RestController
@RequestMapping("/shifts")
public class ShiftController {
    private final ShiftService shiftService;

    @PostMapping("/open")
    public ResponseEntity<ShiftDto> openShift(
        @Valid @RequestBody OpenShiftRequest request,
        UriComponentsBuilder uriBuilder) {
        var shift = shiftService.openShift(request);
        var uri = uriBuilder.path("/shifts/{id}").buildAndExpand(shift.getId()).toUri();

        return ResponseEntity.created(uri).body(shift);
    }

    @GetMapping("/{id}/summary")
    public ShiftSummaryDto getShiftSummary(@PathVariable Long id) {
        return shiftService.getShiftSummary(id);
    }

    @PostMapping("/{id}/close")
    public ShiftDto closeShift(@PathVariable Long id, @Valid @RequestBody CloseShiftRequest request) {
        return shiftService.closeShift(id, request);
    }

    @GetMapping("/current")
    public ShiftDto currentShift() {
        return shiftService.getCurrentShift();
    }

    @ExceptionHandler(ShiftAlreadyOpenException.class)
    public ResponseEntity<ErrorDto> handleShiftAlreadyOpen(ShiftAlreadyOpenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(ShiftAlreadyClosedException.class)
    public ResponseEntity<ErrorDto> handleShiftAlreadyClosed(ShiftAlreadyClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleShiftNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidShiftRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidShiftRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(InvalidAuthorizationTokenException.class)
    public ResponseEntity<ErrorDto> handleInvalidAuthorizationToken(InvalidAuthorizationTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorDto(ex.getMessage()));
    }
}
