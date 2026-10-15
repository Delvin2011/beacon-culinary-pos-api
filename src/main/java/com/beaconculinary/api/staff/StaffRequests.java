package com.beaconculinary.api.staff;

import com.beaconculinary.api.users.Role;
import lombok.Data;

/** Request bodies for /admin/staff. Which credential fields are allowed or required depends on the
 * role (StaffRolePolicy); the service validates them and reports field-level errors. */
public final class StaffRequests {
    private StaffRequests() {
    }

    @Data
    public static class Create {
        private String name;
        private Role role;
        private String email;
        private String password;
        private String pin;
    }

    /** Name, role, email and active. password and pin are only needed to supply a credential the
     * new role requires and the person doesn't have yet (and a PIN is always needed when promoting
     * someone to ADMIN, so admin PINs can be kept unique). */
    @Data
    public static class Update {
        private String name;
        private Role role;
        private String email;
        private Boolean active;
        private String password;
        private String pin;
    }

    @Data
    public static class SetPassword {
        private String password;
    }

    @Data
    public static class SetPin {
        private String pin;
    }
}
