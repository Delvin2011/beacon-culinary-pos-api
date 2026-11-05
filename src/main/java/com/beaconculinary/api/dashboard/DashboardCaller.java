package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.users.Role;

/** Who is asking: what they may see decides which sections, items and fields they get. */
public record DashboardCaller(long userId, Role role) {
    public boolean has(DashboardPermission permission) {
        return permission.grantedTo(role);
    }

    /** Money ("value") fields need the sales permission. */
    public boolean seesValues() {
        return has(DashboardPermission.REPORTS_SALES);
    }
}
