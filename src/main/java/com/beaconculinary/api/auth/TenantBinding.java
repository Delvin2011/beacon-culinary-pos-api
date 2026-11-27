package com.beaconculinary.api.auth;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * T1-5: access and refresh tokens are bound to the customer they were issued at (docs/2026-11-25-customer-bound-credentials.md).
 * In multi mode a new token carries the customer's id as {@value #CLAIM}, and a token is accepted only where that id is
 * the current customer's (T1-3 sets it from the address); one without the claim is refused. In single mode there is no
 * current customer: no claim, no check, as before. A refusal is 401 {@value #CODE}, logged at WARN (never the token).
 */
public final class TenantBinding {
    public static final String CLAIM = "tid";
    public static final String CODE = "TENANT_MISMATCH";
    /** Set by the JWT filter on a refused bearer token, for the 401 entry point. */
    static final String MISMATCH_ATTRIBUTE = "pos.tenantMismatch";
    private static final String MESSAGE = "This sign-in is for another address. Sign in again.";
    private static final Logger log = LoggerFactory.getLogger(TenantBinding.class);

    private TenantBinding() {
    }

    /** The current customer's id for a new token's claim; null with no current customer (single mode: no claim). */
    static Long currentCustomerId() {
        return CustomerContext.current().map(CurrentCustomer::id).orElse(null);
    }

    /** Whether this token may be used here; logs a WARN with the customer ids when it may not. */
    static boolean accepted(Jwt jwt, String kind) {
        var current = CustomerContext.current();
        if (current.isEmpty()) {
            return true;
        }
        var tid = jwt.getTenantId();
        if (tid != null && tid == current.get().id()) {
            return true;
        }
        log.warn("{}: a {} issued for customer {} was presented at customer {}", CODE, kind,
                tid == null ? "(none)" : tid, current.get().slug());
        return false;
    }

    /** The 401 answer, written directly (the security entry point). */
    static void write(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorDto(MESSAGE, CODE));
    }

    /** A refresh cookie issued at another customer. */
    public static class MismatchException extends RuntimeException {
        MismatchException() {
            super(CODE);
        }
    }

    /** The same 401 for a refused refresh. */
    @RestControllerAdvice
    public static class MismatchExceptionHandler {
        @ExceptionHandler(MismatchException.class)
        public ResponseEntity<ErrorDto> handle() {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                    .body(new ErrorDto(MESSAGE, CODE));
        }
    }
}
