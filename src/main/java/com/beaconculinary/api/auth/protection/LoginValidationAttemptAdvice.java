package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.admin.AdminController;
import com.beaconculinary.api.admin.AuthorizeRequest;
import com.beaconculinary.api.auth.AuthController;
import com.beaconculinary.api.auth.LoginRequest;
import com.beaconculinary.api.auth.PinLoginRequest;
import com.beaconculinary.api.common.GlobalExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * Records login requests that fail {@code @Valid} as VALIDATION attempts. Validation runs before the
 * service, so the service never sees these requests. The response is produced by the existing
 * {@link GlobalExceptionHandler} method, so status and body are exactly as before.
 */
@ControllerAdvice(assignableTypes = {AuthController.class, AdminController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginValidationAttemptAdvice {
    private final LoginAttemptService loginAttempts;
    private final GlobalExceptionHandler globalExceptionHandler;

    public LoginValidationAttemptAdvice(LoginAttemptService loginAttempts, GlobalExceptionHandler globalExceptionHandler) {
        this.loginAttempts = loginAttempts;
        this.globalExceptionHandler = globalExceptionHandler;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(MethodArgumentNotValidException exception) {
        var target = exception.getBindingResult().getTarget();
        if (target instanceof LoginRequest login) {
            loginAttempts.recordValidationFailure(LoginMethod.PASSWORD, LoginAttemptService.emailIdentifier(login.getEmail()), null);
        } else if (target instanceof PinLoginRequest pin) {
            var cashierId = pin.getCashierId();
            loginAttempts.recordValidationFailure(LoginMethod.PIN, cashierId == null ? null : cashierId.toString(), null);
        } else if (target instanceof AuthorizeRequest) {
            var caller = currentUserId();
            loginAttempts.recordValidationFailure(LoginMethod.MANAGER_PIN, caller == null ? null : caller.toString(), caller);
        }
        return globalExceptionHandler.handleValidationErrors(exception);
    }

    private static Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}
