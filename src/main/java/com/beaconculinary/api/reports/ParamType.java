package com.beaconculinary.api.reports;

/** Report parameter kinds. DATE = "yyyy-MM-dd" Johannesburg trading day; ENUM = one of the
 * spec's options; BOOLEAN = "true" or "false"; USER = a user id (picked from GET /users);
 * ID = a database id, one of the spec's {value, label} options (filled from the database when
 * the catalogue is requested); LIMIT = "ALL" or a positive whole number, with the spec's options
 * as the suggested choices; INT (R3) = an optional whole number, e.g. a check number. */
public enum ParamType {
    DATE,
    ENUM,
    BOOLEAN,
    USER,
    ID,
    LIMIT,
    INT
}
