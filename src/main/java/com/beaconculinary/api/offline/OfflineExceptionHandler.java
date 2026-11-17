package com.beaconculinary.api.offline;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** O3: renders {@link OfflineException} as ErrorDto {error, code, details?} with its status. */
@RestControllerAdvice
public class OfflineExceptionHandler {
    @ExceptionHandler(OfflineException.class)
    public ResponseEntity<ErrorDto> handle(OfflineException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ErrorDto(ex.getMessage(), ex.getCode(), ex.getDetails()));
    }
}
