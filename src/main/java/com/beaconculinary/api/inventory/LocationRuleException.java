package com.beaconculinary.api.inventory;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * T0-5c: a stock-location rule a client must branch on, rendered by GlobalExceptionHandler as
 * {@code {error, code, details}} with its status (docs/2026-11-21-t0-5c-locations-api-contract.md).
 */
public class LocationRuleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public LocationRuleException(HttpStatus status, String code, String message, Map<String, Object> details) {
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

    /** 400 ISSUE_LOCATION_NOT_SECONDARY: an issue can only be requested by a secondary location;
     * the primary always supplies. */
    public static LocationRuleException issueLocationNotSecondary(Location location) {
        return new LocationRuleException(HttpStatus.BAD_REQUEST, "ISSUE_LOCATION_NOT_SECONDARY",
                "Stock is issued from " + location.getName() + " (the primary location) to a secondary location; "
                        + "choose the secondary location that needs the stock.",
                Map.of("locationId", location.getId(), "locationName", location.getName()));
    }

    /** 400 LOCATION_INACTIVE: the chosen location exists but has been deactivated. */
    public static LocationRuleException inactive(Location location) {
        return new LocationRuleException(HttpStatus.BAD_REQUEST, "LOCATION_INACTIVE",
                location.getName() + " is no longer active.",
                Map.of("locationId", location.getId(), "locationName", location.getName()));
    }

    /** 400 LOCATION_NOT_FOUND: no location has this id. */
    public static LocationRuleException notFound(Long locationId) {
        return new LocationRuleException(HttpStatus.BAD_REQUEST, "LOCATION_NOT_FOUND",
                "locationId does not exist.", Map.of("locationId", locationId));
    }
}
