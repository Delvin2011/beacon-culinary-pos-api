package com.beaconculinary.api.common;

import com.beaconculinary.api.auth.invite.InviteRuleException;
import com.beaconculinary.api.inventory.LocationRuleException;
import com.beaconculinary.api.menu.MenuRuleException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableMessage() {
        return ResponseEntity.badRequest().body(
            new ErrorDto("Invalid request body")
        );
    }

    @ExceptionHandler(MenuRuleException.class)
    public ResponseEntity<ErrorDto> handleMenuRule(MenuRuleException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ErrorDto(exception.getMessage(), exception.getCode()));
    }

    // T0-5c: stock-location rules (stock requests now, /admin/locations later), with details.
    @ExceptionHandler(LocationRuleException.class)
    public ResponseEntity<ErrorDto> handleLocationRule(LocationRuleException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorDto(exception.getMessage(), exception.getCode(), exception.getDetails()));
    }

    // T0-6a: invite refusals (public invite endpoints and staff invites).
    @ExceptionHandler(InviteRuleException.class)
    public ResponseEntity<ErrorDto> handleInviteRule(InviteRuleException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorDto(exception.getMessage(), exception.getCode(), exception.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException exception
    ) {
        var errors = new HashMap<String, String>();

        exception.getBindingResult().getFieldErrors().forEach(error -> {
            errors.put(error.getField(), error.getDefaultMessage());
        });

        return ResponseEntity.badRequest().body(errors);
    }
}
