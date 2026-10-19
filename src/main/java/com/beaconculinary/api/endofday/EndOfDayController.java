package com.beaconculinary.api.endofday;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.reports.ReportEnvelope;
import com.beaconculinary.api.shifts.ShiftNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Reports R3: shift sign-off and Close Day. ADMIN only, through AdminSecurityRules' /admin/**
 * rule; GET /shifts/{id}/reading lives in {@link ShiftReadingController}.
 */
@AllArgsConstructor
@RestController
public class EndOfDayController {
    private final ShiftSignoffService signoffService;
    private final CloseDayService closeDayService;

    public record NoteRequest(String note) {
    }

    @PostMapping("/admin/shifts/{id}/signoff")
    public ShiftSignoffService.SignoffDto signOff(@PathVariable long id, @RequestBody(required = false) NoteRequest request) {
        return signoffService.signOff(id, request == null ? null : request.note());
    }

    @PostMapping("/admin/shifts/{id}/signoff/revoke")
    public ShiftSignoffService.SignoffDto revoke(@PathVariable long id, @RequestBody(required = false) NoteRequest request) {
        return signoffService.revoke(id, request == null ? null : request.note());
    }

    /** 200 with the Z envelope, or 409 CLOSE_DAY_BLOCKED with every blocker. */
    @PostMapping("/admin/days/{tradingDay}/close")
    public ReportEnvelope closeDay(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradingDay) {
        return closeDayService.close(tradingDay);
    }

    @ExceptionHandler(EndOfDayConflictException.class)
    public ResponseEntity<EndOfDayErrorDto> handleConflict(EndOfDayConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(EndOfDayErrorDto.of(ex));
    }

    @ExceptionHandler(InvalidEndOfDayRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalid(InvalidEndOfDayRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
