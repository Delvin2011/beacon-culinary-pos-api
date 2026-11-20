package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** T0-5a: field errors as a 400 field map; rules as {error, code, details}. */
@RestControllerAdvice
public class MealPeriodExceptionHandler {
    @ExceptionHandler(MealPeriodValidationException.class)
    public ResponseEntity<Map<String, String>> handle(MealPeriodValidationException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }

    @ExceptionHandler(MealPeriodRuleException.class)
    public ResponseEntity<ErrorDto> handle(MealPeriodRuleException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ErrorDto(ex.getMessage(), ex.getCode(), ex.getDetails()));
    }
}
