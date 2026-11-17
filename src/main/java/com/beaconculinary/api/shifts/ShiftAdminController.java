package com.beaconculinary.api.shifts;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.endofday.EndOfDayConflictException;
import com.beaconculinary.api.endofday.EndOfDayErrorDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** O2 R11: force close and late count. ADMIN only, through the /admin/** rule in AdminSecurityRules. */
@RestController
@RequestMapping("/admin/shifts")
public class ShiftAdminController {
    private final ShiftAdminService shiftAdminService;

    public ShiftAdminController(ShiftAdminService shiftAdminService) {
        this.shiftAdminService = shiftAdminService;
    }

    @PostMapping("/{id}/force-close")
    public ShiftDto forceClose(@PathVariable long id, @Valid @RequestBody ShiftAdminRequests.ForceClose request) {
        return shiftAdminService.forceClose(id, request);
    }

    @PostMapping("/{id}/count")
    public ShiftDto lateCount(@PathVariable long id, @Valid @RequestBody ShiftAdminRequests.LateCount request) {
        return shiftAdminService.lateCount(id, request);
    }

    @ExceptionHandler(ShiftAdminService.ShiftFieldException.class)
    public ResponseEntity<Map<String, String>> handleField(ShiftAdminService.ShiftFieldException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }

    @ExceptionHandler(ShiftAlreadyClosedException.class)
    public ResponseEntity<ErrorDto> handleAlreadyClosed(ShiftAlreadyClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage(), "SHIFT_ALREADY_CLOSED"));
    }

    @ExceptionHandler(EndOfDayConflictException.class)
    public ResponseEntity<EndOfDayErrorDto> handleEndOfDay(EndOfDayConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(EndOfDayErrorDto.of(ex));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
