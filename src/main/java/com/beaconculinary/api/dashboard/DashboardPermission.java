package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.users.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * The permissions the dashboard checks, named as in U2. Until U2 ships they map onto today's
 * roles, mirroring what each role can already open elsewhere (nothing here grants new access):
 * money is ADMIN-only; the Head Chef sees the kitchen-facing sections, stock and planning;
 * approving stock is STOCK_ADMIN and up. Replace this mapping with U2's grants when it lands.
 */
public enum DashboardPermission {
    DASHBOARD_VIEW(Role.ADMIN, Role.HEAD_CHEF),
    REPORTS_SALES(Role.ADMIN),
    REPORTS_CASH(Role.ADMIN),
    REPORTS_ACCOUNTS(Role.ADMIN),
    SIGN_OFF_SHIFTS(Role.ADMIN),
    RUN_EOD(Role.ADMIN),
    STOCK_VIEW(Role.ADMIN, Role.HEAD_CHEF, Role.STOCK_ADMIN),
    DAILY_PLANNING(Role.ADMIN, Role.HEAD_CHEF),
    /** Actioning stock requests or reviewing stock takes. */
    STOCK_APPROVE(Role.ADMIN, Role.STOCK_ADMIN),
    STOCK_REQUESTS_VIEW_ALL(Role.ADMIN, Role.STOCK_ADMIN);

    private final Set<Role> roles;

    DashboardPermission(Role first, Role... rest) {
        this.roles = EnumSet.of(first, rest);
    }

    public boolean grantedTo(Role role) {
        return roles.contains(role);
    }
}
