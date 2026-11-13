package com.beaconculinary.api.devices;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Device errors as ErrorDto {error, code, details?}; field errors as a 400 field map. Only device
 * code throws these, so no other response changes.
 */
@RestControllerAdvice
public class DeviceExceptionHandler {

    @ExceptionHandler(DeviceException.class)
    public ResponseEntity<ErrorDto> handle(DeviceException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorDto(exception.getMessage(), exception.getCode(), exception.getDetails()));
    }

    @ExceptionHandler(DeviceValidationException.class)
    public ResponseEntity<Map<String, String>> handle(DeviceValidationException exception) {
        return ResponseEntity.badRequest().body(exception.getErrors());
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Void> handle(DeviceNotFoundException exception) {
        return ResponseEntity.notFound().build();
    }
}
