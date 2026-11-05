package com.beaconculinary.api.dashboard;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /admin/dashboard/layout and GET /admin/dashboard/sections/{key}?range=today|7d|month. Read
 * only. The URL is open to every role with DASHBOARD_VIEW (AdminSecurityRules); each section then
 * checks its own permission in {@link DashboardService}.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/layout")
    public DashboardService.Layout layout() {
        return dashboardService.layout();
    }

    @GetMapping("/sections/{key}")
    public DashboardService.SectionEnvelope section(@PathVariable String key, @RequestParam Map<String, String> params) {
        return dashboardService.section(key, params);
    }

    @ExceptionHandler(DashboardSectionNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleForbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(DashboardParamException.class)
    public ResponseEntity<Map<String, String>> handleBadParams(DashboardParamException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }

    /** One section failed; the page's other sections are separate requests and unaffected. */
    @ExceptionHandler(DashboardSectionFailedException.class)
    public ResponseEntity<Map<String, Object>> handleFailed(DashboardSectionFailedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(DashboardService.error(ex.getKey(), "This section could not be loaded."));
    }
}
