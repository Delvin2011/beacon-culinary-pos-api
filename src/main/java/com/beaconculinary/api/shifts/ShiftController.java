package com.beaconculinary.api.shifts;

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

    @PostMapping("/{id}/close")
    public ShiftDto closeShift(@PathVariable Long id) {
        return shiftService.closeShift(id);
    }

    @GetMapping("/current")
    public ShiftDto currentShift() {
        return shiftService.getCurrentShift();
    }

    @ExceptionHandler(ShiftAlreadyOpenException.class)
    public ResponseEntity<ErrorDto> handleShiftAlreadyOpen(ShiftAlreadyOpenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleShiftNotFound() {
        return ResponseEntity.notFound().build();
    }
}
