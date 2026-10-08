package com.beaconculinary.api.reports;

import lombok.AllArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Reports R1 §1.1. /admin/** keeps these ADMIN-only at the URL level for now; on top of that,
 * each report's allowedRoles is enforced in ReportService, so the registry stays the source of
 * truth when later stages open specific reports to other roles.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/reports")
public class ReportController {
    private final ReportService reportService;

    @GetMapping
    public List<ReportCatalogueEntry> catalogue() {
        return reportService.catalogue();
    }

    @GetMapping("/{key}")
    public ReportEnvelope run(@PathVariable String key, @RequestParam Map<String, String> params) {
        return reportService.run(key, params);
    }

    @GetMapping("/{key}/export")
    public ResponseEntity<byte[]> export(@PathVariable String key, @RequestParam Map<String, String> params) {
        var file = reportService.export(key, params);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    /** Field-level messages, keyed by parameter name: { "to": "Range can't exceed 366 days." }. */
    @ExceptionHandler(ReportParamException.class)
    public ResponseEntity<Map<String, String>> handleInvalidParams(ReportParamException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }
}
