package com.beaconculinary.api.sessions;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** O2: 401 {error, code: SESSION_ENDED, details: {reason}}. Other 401s keep their empty body. */
@RestControllerAdvice
public class SessionExceptionHandler {
    @ExceptionHandler(SessionEndedException.class)
    public ResponseEntity<ErrorDto> handle(SessionEndedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorDto(ex.getMessage(), "SESSION_ENDED", Map.of("reason", ex.getReason().name())));
    }
}
