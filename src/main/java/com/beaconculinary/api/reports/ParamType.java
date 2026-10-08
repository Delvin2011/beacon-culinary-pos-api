package com.beaconculinary.api.reports;

/** Report parameter kinds. DATE = "yyyy-MM-dd" Johannesburg trading day; ENUM = one of the
 * spec's options; BOOLEAN = "true" or "false"; USER = a user id (picked from GET /users). */
public enum ParamType {
    DATE,
    ENUM,
    BOOLEAN,
    USER
}
