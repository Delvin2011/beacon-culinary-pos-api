package com.beaconculinary.api.receipts;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** O2b: renders {@link ReceiptSettingsValidationException} as a 400 field map, like bean validation. */
@RestControllerAdvice
public class ReceiptSettingsExceptionHandler {
    @ExceptionHandler(ReceiptSettingsValidationException.class)
    public ResponseEntity<Map<String, String>> handle(ReceiptSettingsValidationException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }
}
