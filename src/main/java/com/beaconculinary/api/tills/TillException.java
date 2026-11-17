package com.beaconculinary.api.tills;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O2: a till or shift rule refusal, returned as ErrorDto {error, code, details?} by
 * {@link TillExceptionHandler}. Codes are listed in section 6 of
 * docs/2026-11-17-o2-tills-shifts-api-contract.md.
 */
public class TillException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public TillException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
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

    /** This till already has an open shift (anyone's, the caller's included). */
    public static TillException shiftAlreadyOpen(long shiftId, String ownerName, Till till) {
        var details = new LinkedHashMap<String, Object>();
        details.put("shiftId", shiftId);
        details.put("ownerName", ownerName);
        details.put("tillCode", till.code());
        var where = till.registered() ? till.code() : "The unregistered till";
        return new TillException(HttpStatus.CONFLICT, "SHIFT_ALREADY_OPEN",
                where + " already has an open shift (" + ownerName + ").", details);
    }

    /** The caller has an open shift on another till. Sign-in says so too (step 5). */
    public static TillException shiftOpenOnOtherTill(Till other, String advice) {
        var details = new LinkedHashMap<String, Object>();
        details.put("tillCode", other.code());
        details.put("tillName", other.name());
        var where = other.registered() ? other.code() + " (" + other.name() + ")" : "the unregistered till";
        return new TillException(HttpStatus.CONFLICT, "SHIFT_OPEN_ON_OTHER_TILL",
                "You have a shift open on " + where + ". " + advice, details);
    }
}
