package com.beaconculinary.api.menu;

import org.springframework.http.HttpStatus;

/** A catalogue or planning rule the client must branch on: a stable code and its HTTP status
 * (e.g. 400 MEAL_NOT_ALLOWED_IN_PERIOD, 409 CATEGORY_HAS_ITEMS). Handled globally. */
public class MenuRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MenuRuleException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    static MenuRuleException badRequest(String code, String message) {
        return new MenuRuleException(HttpStatus.BAD_REQUEST, code, message);
    }

    static MenuRuleException conflict(String code, String message) {
        return new MenuRuleException(HttpStatus.CONFLICT, code, message);
    }
}
