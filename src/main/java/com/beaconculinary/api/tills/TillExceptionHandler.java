package com.beaconculinary.api.tills;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** O2: renders {@link TillException} as ErrorDto {error, code, details?} with its status. */
@RestControllerAdvice
public class TillExceptionHandler {
    @ExceptionHandler(TillException.class)
    public ResponseEntity<ErrorDto> handle(TillException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ErrorDto(ex.getMessage(), ex.getCode(), ex.getDetails()));
    }
}
