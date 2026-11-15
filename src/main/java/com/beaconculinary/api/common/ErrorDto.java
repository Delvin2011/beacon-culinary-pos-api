package com.beaconculinary.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
public class ErrorDto {
    private String error;

    // Stable machine-readable code for errors a client must branch on (e.g. NO_OPEN_SHIFT vs
    // TILL_IN_USE, both 409). Omitted from the body when null, so existing errors are unchanged.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    // Extra data for some errors (e.g. deviceId and deviceName for DEVICE_SERIAL_EXISTS). Omitted
    // when null or empty, so existing errors are unchanged.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> details;

    public ErrorDto(String error, String code, Map<String, Object> details) {
        this.error = error;
        this.code = code;
        this.details = details;
    }

    public ErrorDto(String error, String code) {
        this(error, code, null);
    }

    public ErrorDto(String error) {
        this(error, null, null);
    }
}
