package com.beaconculinary.api.auth;

import com.beaconculinary.api.common.ErrorDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T1-3: sign-in with the database unavailable is 503 {@code DATABASE_UNAVAILABLE}, so staff see "server unavailable"
 * (the frontend treats any 5xx on sign-in so), not "wrong password". Logged at ERROR; the line carries the request id.
 */
@Slf4j
@RestControllerAdvice
public class SignInUnavailableExceptionHandler {

    @ExceptionHandler(SignInUnavailableException.class)
    public ResponseEntity<ErrorDto> handle(SignInUnavailableException exception) {
        log.error("Sign-in failed: the database is unavailable", exception.getCause());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorDto("Sign-in is unavailable right now. Try again shortly.", SignInUnavailableException.CODE));
    }
}
