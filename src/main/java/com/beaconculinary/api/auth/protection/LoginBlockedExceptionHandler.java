package com.beaconculinary.api.auth.protection;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP 429 for a login-protection refusal: body {code, message, retryAfterSeconds}, and a
 * Retry-After header (seconds) whenever the wait has an end. Only login protection throws
 * {@link LoginBlockedException}, so this handler changes no other response.
 */
@RestControllerAdvice
public class LoginBlockedExceptionHandler {

    @ExceptionHandler(LoginBlockedException.class)
    public ResponseEntity<LoginProtectionErrorDto> handle(LoginBlockedException exception) {
        var body = exception.body();
        var response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON);
        if (body.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(body.retryAfterSeconds()));
        }
        return response.body(body);
    }
}
