package com.beaconculinary.api.offline.exceptions;

import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.Detail;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.NoteRequest;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** O4: exception review (contract section 8). ADMIN only, through the /admin/** rule in AdminSecurityRules. */
@RestController
public class SaleExceptionController {
    private final SaleExceptionService service;

    public SaleExceptionController(SaleExceptionService service) {
        this.service = service;
    }

    @GetMapping("/admin/sale-exceptions")
    public Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String severity,
                     @RequestParam(required = false) String code,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradingDay,
                     @RequestParam(required = false) UUID deviceId, @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        return service.list(status, severity, code, tradingDay, deviceId, page, size);
    }

    @GetMapping("/admin/sale-exceptions/{id}")
    public Detail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/admin/sale-exceptions/{id}/resolve")
    public Detail resolve(@PathVariable long id, @RequestBody(required = false) NoteRequest request) {
        return service.resolve(id, request == null ? null : request.note());
    }

    @PostMapping("/admin/sale-exceptions/{id}/approve-account")
    public Detail approveAccount(@PathVariable long id, @RequestBody(required = false) NoteRequest request) {
        return service.approveAccount(id, request == null ? null : request.note());
    }

    @ExceptionHandler(SaleExceptionService.InvalidRequest.class)
    public ResponseEntity<Map<String, String>> invalid(SaleExceptionService.InvalidRequest ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }
}
