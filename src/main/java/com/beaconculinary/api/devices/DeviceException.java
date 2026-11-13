package com.beaconculinary.api.devices;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A device-registration error, returned as ErrorDto {error, code, details?} with the given status
 * by {@link DeviceExceptionHandler}. Codes are listed in the device registration API contract.
 */
public class DeviceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public DeviceException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public DeviceException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
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

    // ---- the codes ----

    public static DeviceException notRegistered() {
        return new DeviceException(HttpStatus.FORBIDDEN, "DEVICE_NOT_REGISTERED", "This till isn't registered. Ask a manager.");
    }

    public static DeviceException reauthRequired() {
        return new DeviceException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Sign in again with your password to manage tills.");
    }

    public static DeviceException serialInUse(DeviceStore.DeviceRow device) {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_SERIAL_IN_USE",
                "This serial number belongs to an active till. Revoke its browser first.", deviceDetails(device));
    }

    public static DeviceException serialExists(DeviceStore.DeviceRow device) {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_SERIAL_EXISTS",
                "A till with this serial number is already registered. Confirm to add this browser to it.", deviceDetails(device));
    }

    public static DeviceException joinMismatch() {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_JOIN_MISMATCH",
                "That till doesn't match this serial number.");
    }

    public static DeviceException joinDisabled() {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_JOIN_DISABLED",
                "Adding more browsers to a till is turned off.");
    }

    public static DeviceException assetTagInUse(DeviceStore.DeviceRow device) {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_ASSET_TAG_IN_USE",
                "This asset tag belongs to another active till.", deviceDetails(device));
    }

    public static DeviceException alreadyRegistered(DeviceContext context) {
        var details = new LinkedHashMap<String, Object>();
        details.put("deviceId", context.deviceId());
        details.put("deviceName", context.deviceName());
        details.put("credentialId", context.credentialId());
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_ALREADY_REGISTERED",
                "This browser is already registered to a till. Revoke it first.", details);
    }

    public static DeviceException revoked() {
        return new DeviceException(HttpStatus.CONFLICT, "DEVICE_REVOKED", "This till or browser has been revoked.");
    }

    private static Map<String, Object> deviceDetails(DeviceStore.DeviceRow device) {
        var details = new LinkedHashMap<String, Object>();
        details.put("deviceId", device.id());
        details.put("deviceName", device.name());
        return details;
    }
}
