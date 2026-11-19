package com.beaconculinary.api.settings;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * T0-4a: validation as a 400 field map (like receipt settings); an unknown key on reset as 404
 * UNKNOWN_SETTING. REAUTH_REQUIRED is a DeviceException, rendered by DeviceExceptionHandler.
 */
@RestControllerAdvice
public class SiteSettingsExceptionHandler {
    @ExceptionHandler(SiteSettingsValidationException.class)
    public ResponseEntity<Map<String, String>> handle(SiteSettingsValidationException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }

    @ExceptionHandler(UnknownSettingException.class)
    public ResponseEntity<ErrorDto> handle(UnknownSettingException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDto(ex.getMessage(), "UNKNOWN_SETTING"));
    }
}
