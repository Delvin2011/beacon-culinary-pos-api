package com.beaconculinary.api.staff;

import com.beaconculinary.api.common.ErrorDto;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Staff management. ADMIN only, through the /admin/** rule in AdminSecurityRules. */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/staff")
public class StaffController {
    private final StaffService staffService;

    /** The roles staff can be given and which credentials each uses (REQUIRED / OPTIONAL / NONE). */
    @GetMapping("/roles")
    public List<StaffRolePolicy.RoleRule> roles() {
        return staffService.roles();
    }

    @GetMapping
    public List<StaffDto> list() {
        return staffService.list();
    }

    @GetMapping("/{id}")
    public StaffDto get(@PathVariable Long id) {
        return staffService.get(id);
    }

    @PostMapping
    public ResponseEntity<StaffDto> create(@RequestBody StaffRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staffService.create(request));
    }

    @PutMapping("/{id}")
    public StaffDto update(@PathVariable Long id, @RequestBody StaffRequests.Update request) {
        return staffService.update(id, request);
    }

    @PostMapping("/{id}/password")
    public StaffDto setPassword(@PathVariable Long id, @RequestBody StaffRequests.SetPassword request) {
        return staffService.setPassword(id, request);
    }

    @PostMapping("/{id}/pin")
    public StaffDto setPin(@PathVariable Long id, @RequestBody StaffRequests.SetPin request) {
        return staffService.setPin(id, request);
    }

    /** Field-level messages keyed by field: { "pin": "PIN must be exactly 6 digits." }. */
    @ExceptionHandler(StaffExceptions.Invalid.class)
    public ResponseEntity<Map<String, String>> handleInvalid(StaffExceptions.Invalid ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }

    @ExceptionHandler(StaffExceptions.Conflict.class)
    public ResponseEntity<ErrorDto> handleConflict(StaffExceptions.Conflict ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(ex.getMessage(), ex.getCode()));
    }

    @ExceptionHandler(StaffExceptions.NotFound.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
