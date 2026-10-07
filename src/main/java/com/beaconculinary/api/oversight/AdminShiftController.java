package com.beaconculinary.api.oversight;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.shifts.ShiftNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

/** POS Oversight Part A. ADMIN-only via the /admin/** catch-all in AdminSecurityRules — it
 * exposes cash and variance data, so it must never fall under the STOCK_ADMIN exceptions. */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/shifts")
public class AdminShiftController {
    private final ShiftActivityService shiftActivityService;

    /** from/to are Africa/Johannesburg calendar days on opened_at, inclusive; default last 7 days. */
    @GetMapping
    public PageDto<ShiftListRowDto> listShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) ShiftDisplayStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return shiftActivityService.listShifts(from, to, ownerId, status, page, size);
    }

    @GetMapping("/{id}/activity")
    public ShiftActivityDto getActivity(@PathVariable Long id) {
        return shiftActivityService.getActivity(id);
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleShiftNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidShiftActivityRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidShiftActivityRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleBadParameter(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto("Invalid value for " + ex.getName() + "."));
    }
}
