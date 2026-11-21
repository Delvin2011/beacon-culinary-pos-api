package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.auth.protection.LoginOutcome;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * T0-6a: an invite refusal a client branches on, rendered by GlobalExceptionHandler as
 * {@code {error, code, details}} with its status (docs/2026-11-21-t0-6-invites-api-contract.md).
 * {@link #outcome()} is how the attempt is recorded for login protection (public endpoints only).
 */
public class InviteRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;
    private final LoginOutcome outcome;

    public InviteRuleException(HttpStatus status, String code, String message, Map<String, Object> details,
                               LoginOutcome outcome) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.outcome = outcome;
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

    public LoginOutcome outcome() {
        return outcome;
    }

    private static final String INVALID_MESSAGE = "This link isn't valid. Ask your manager for a new one.";

    /** 404 INVITE_INVALID for a token that matches no invite: counts as a failure (UNKNOWN_USER). */
    public static InviteRuleException unknown() {
        return new InviteRuleException(HttpStatus.NOT_FOUND, "INVITE_INVALID", INVALID_MESSAGE, null,
                LoginOutcome.UNKNOWN_USER);
    }

    /**
     * 404 INVITE_INVALID for a real invite that no longer applies: revoked or replaced, the person
     * deactivated, or their role changed so the link no longer fits. Not a failure (the caller held
     * a real token).
     */
    public static InviteRuleException noLongerValid() {
        return new InviteRuleException(HttpStatus.NOT_FOUND, "INVITE_INVALID", INVALID_MESSAGE, null,
                LoginOutcome.VALIDATION);
    }

    /** 410 INVITE_EXPIRED. */
    public static InviteRuleException expired() {
        return new InviteRuleException(HttpStatus.GONE, "INVITE_EXPIRED",
                "This link has expired. Ask your manager for a new one.", null, LoginOutcome.VALIDATION);
    }

    /** 410 INVITE_USED. */
    public static InviteRuleException used() {
        return new InviteRuleException(HttpStatus.GONE, "INVITE_USED",
                "This link has already been used. Sign in with the details you set.", null, LoginOutcome.VALIDATION);
    }

    /** 400 INVITE_CREDENTIALS_INVALID with details.fields (field name to message). */
    public static InviteRuleException credentialsInvalid(Map<String, String> fields) {
        return new InviteRuleException(HttpStatus.BAD_REQUEST, "INVITE_CREDENTIALS_INVALID",
                "Check the highlighted fields.", Map.of("fields", fields), LoginOutcome.VALIDATION);
    }

    /** 409 with a staff conflict code (PIN_IN_USE). */
    public static InviteRuleException conflict(String code, String message) {
        return new InviteRuleException(HttpStatus.CONFLICT, code, message, null, LoginOutcome.VALIDATION);
    }
}
