package com.beaconculinary.api.devices;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The POS till behind the current request, as resolved from its deviceToken cookie by
 * {@link DeviceResolutionFilter}. Stored as a request attribute.
 *
 * @param status       what the cookie resolved to
 * @param deviceId     the till (machine), when VALID
 * @param credentialId the browser, when VALID (or the revoked/expired one, for alerts)
 */
public record DeviceContext(Status status, String deviceId, String credentialId, String deviceName) {
    static final String ATTRIBUTE = DeviceContext.class.getName();
    public static final DeviceContext NONE = new DeviceContext(Status.NONE, null, null, null);

    public enum Status {
        /** No cookie. */
        NONE,
        /** An active, non-expired browser of an active till. */
        VALID,
        /** A cookie that matches no browser. */
        INVALID,
        /** The browser has not been seen for idle-expiry-days. */
        EXPIRED,
        /** The browser or its till was revoked. */
        REVOKED,
        /** Resolution failed (logged); treated like no cookie. */
        ERROR
    }

    public boolean valid() {
        return status == Status.VALID;
    }

    /** The context of the current request; NONE outside a request or when not resolved. */
    public static DeviceContext current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return NONE;
        }
        var value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof DeviceContext context ? context : NONE;
    }
}
