package com.beaconculinary.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class ErrorDto {
    private String error;

    // Stable machine-readable code for errors a client must branch on (e.g. NO_OPEN_SHIFT vs
    // TILL_IN_USE, both 409). Omitted from the body when null, so existing errors are unchanged.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public ErrorDto(String error) {
        this(error, null);
    }
}
