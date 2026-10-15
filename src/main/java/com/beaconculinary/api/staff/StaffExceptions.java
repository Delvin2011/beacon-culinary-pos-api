package com.beaconculinary.api.staff;

import java.util.Map;

public final class StaffExceptions {
    private StaffExceptions() {
    }

    /** Field-level problems, returned as a 400 map of field name to message. */
    public static class Invalid extends RuntimeException {
        private final Map<String, String> errors;

        public Invalid(Map<String, String> errors) {
            super("Invalid staff details: " + errors);
            this.errors = Map.copyOf(errors);
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    /** A 409 with a stable code: EMAIL_IN_USE, PIN_IN_USE or LAST_ADMIN. */
    public static class Conflict extends RuntimeException {
        private final String code;

        public Conflict(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static class NotFound extends RuntimeException {
    }
}
