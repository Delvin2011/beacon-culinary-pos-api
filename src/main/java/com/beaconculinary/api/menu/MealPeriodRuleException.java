package com.beaconculinary.api.menu;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * T0-5a: a meal period rule the client branches on, with details: 404 MEAL_PERIOD_NOT_FOUND,
 * 409 PERIOD_OVERLAP, PERIOD_IN_USE and ALL_DAY_PROTECTED (contract section 8).
 */
public class MealPeriodRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public MealPeriodRuleException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    static MealPeriodRuleException notFound(long id) {
        return new MealPeriodRuleException(HttpStatus.NOT_FOUND, "MEAL_PERIOD_NOT_FOUND",
                "There is no meal period " + id + ".", null);
    }

    static MealPeriodRuleException conflict(String code, String message, Map<String, Object> details) {
        return new MealPeriodRuleException(HttpStatus.CONFLICT, code, message, details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
