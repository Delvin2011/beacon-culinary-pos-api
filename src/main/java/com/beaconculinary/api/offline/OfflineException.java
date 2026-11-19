package com.beaconculinary.api.offline;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** O3: an offline request refused with a stable code (contract section 4). */
@Getter
public class OfflineException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public OfflineException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static OfflineException disabled() {
        return new OfflineException(HttpStatus.FORBIDDEN, "OFFLINE_DISABLED", "Offline selling is turned off.", null);
    }

    public static OfflineException posSessionRequired() {
        return new OfflineException(HttpStatus.FORBIDDEN, "POS_SESSION_REQUIRED",
                "Sign in on this till to use offline selling.", null);
    }

    public static OfflineException noOpenShift() {
        return new OfflineException(HttpStatus.CONFLICT, "NO_OPEN_SHIFT", "This till has no open shift.", null);
    }

    public static OfflineException notShiftOwner(String ownerName) {
        return new OfflineException(HttpStatus.FORBIDDEN, "NOT_SHIFT_OWNER",
                "Only " + ownerName + ", who owns this till's shift, can get offline access here.", null);
    }
}
