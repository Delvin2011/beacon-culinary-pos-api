package com.beaconculinary.api.users;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** What is left of /users: the two public login-tile lists and changing your own password. Staff are
 * listed, created, edited and deactivated only through /admin/staff (T0-3 removed the old e-commerce
 * GET/POST /users and GET/PUT/DELETE /users/{id}). */
@RestController
@AllArgsConstructor
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    /** Public, pre-login endpoint for the POS login screen's cashier tile grid — no auth, no pagination.
     * Kept for compatibility; equivalent to GET /users/login-tiles?screen=POS. */
    @GetMapping("/cashiers")
    public List<CashierSummaryDto> getCashiers() {
        return userService.getLoginTiles(LoginScreen.POS);
    }

    /** Public, pre-login tile grid for one PIN-login screen: POS (cashiers and admins) or KITCHEN
     * (kitchen staff and the Head Chef, with a PIN set). */
    @GetMapping("/login-tiles")
    public List<CashierSummaryDto> getLoginTiles(@RequestParam LoginScreen screen) {
        return userService.getLoginTiles(screen);
    }

    @PostMapping("/{id}/change-password")
    public void changePassword(
            @PathVariable Long id,
            @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request);
    }

    @ExceptionHandler(NotOwnAccountException.class)
    public ResponseEntity<Void> handleNotOwnAccount() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPassword(InvalidPasswordException ex) {
        return ResponseEntity.badRequest().body(Map.of("newPassword", ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Void> handleUserNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
