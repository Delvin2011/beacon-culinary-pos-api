package com.beaconculinary.api.staff;

import com.beaconculinary.api.auth.invite.InviteAdminDtos;
import com.beaconculinary.api.auth.invite.InviteService;
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
    private final InviteService inviteService;

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

    /** T0-6a: setup INVITE creates the person without credentials and returns their set-up link once. */
    @PostMapping
    public ResponseEntity<StaffDto> create(@RequestBody StaffRequests.Create request) {
        var body = request.getSetup() == StaffRequests.Setup.INVITE
                ? inviteService.createStaffWithInvite(request)
                : staffService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
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

    /** T0-6a: a STAFF_SETUP or RESET link, replacing any open one. The token is in this response only. */
    @PostMapping("/{id}/invite")
    public ResponseEntity<InviteAdminDtos.IssuedInvite> invite(@PathVariable Long id,
                                                               @RequestBody(required = false) InviteAdminDtos.IssueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inviteService.issue(id, request));
    }

    /** T0-6a: revokes the open link, if any (nothing open: no change). */
    @PostMapping("/{id}/invite/revoke")
    public StaffDto revokeInvite(@PathVariable Long id) {
        inviteService.revoke(id);
        return staffService.get(id);
    }

    /** WS1-1: ends sign-in locks and blocks. Body {"scope": "PIN"} for one scope; no body for all. */
    @PostMapping("/{id}/unlock")
    public StaffDto unlock(@PathVariable Long id, @RequestBody(required = false) StaffRequests.Unlock request) {
        return staffService.unlock(id, request);
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
