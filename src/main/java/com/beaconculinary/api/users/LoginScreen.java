package com.beaconculinary.api.users;

import java.util.List;

/** Which PIN-login screen a tile list is for. Each screen shows only the roles that use it. */
public enum LoginScreen {
    /** The till: cashiers and admins. */
    POS(List.of(Role.CASHIER, Role.ADMIN), false),
    /** The kitchen display: kitchen staff and the Head Chef — only those with a PIN set. */
    KITCHEN(List.of(Role.KITCHEN, Role.HEAD_CHEF), true);

    private final List<Role> roles;
    private final boolean requiresPin;

    LoginScreen(List<Role> roles, boolean requiresPin) {
        this.roles = roles;
        this.requiresPin = requiresPin;
    }

    public List<Role> roles() {
        return roles;
    }

    public boolean requiresPin() {
        return requiresPin;
    }
}
