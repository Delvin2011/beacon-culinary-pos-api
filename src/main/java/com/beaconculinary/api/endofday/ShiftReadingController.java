package com.beaconculinary.api.endofday;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.ReportEnvelope;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportService;
import com.beaconculinary.api.reports.definitions.CashierReadingReport;
import com.beaconculinary.api.shifts.ShiftNotFoundException;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Reports R3 §5: the Cashier Reading for the till — the same envelope as the cashier-reading
 * report, for the shift's owner or an ADMIN (403 for anyone else). An open shift's reading is
 * interim. Report-level actions (Sign off) are for managers, so they're left off for a cashier.
 */
@AllArgsConstructor
@RestController
public class ShiftReadingController {
    private final ShiftRepository shiftRepository;
    private final AuthService authService;
    private final EndOfDayStatements statements;
    private final ReportService reportService;
    private final CashierReadingReport cashierReadingReport;

    @GetMapping("/shifts/{id}/reading")
    @Transactional(readOnly = true)
    public ReportEnvelope reading(@PathVariable long id) {
        var shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        var user = authService.getCurrentUser();
        var admin = user.getRole() == Role.ADMIN;
        if (!admin && !shift.getCashier().getId().equals(user.getId())) {
            throw new AccessDeniedException("Only the shift's owner or an admin can see its reading.");
        }
        var result = statements.cashierReading(id);
        if (!admin) {
            result = new ReportResult(null, null, null, result.checks(), null, result.sections(), List.of(), result.statement());
        }
        return reportService.wrap(cashierReadingReport, ReportParams.of(Map.of("shiftId", id)), result);
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
