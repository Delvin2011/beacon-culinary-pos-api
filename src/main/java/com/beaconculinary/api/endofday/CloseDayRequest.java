package com.beaconculinary.api.endofday;

/**
 * O3: the optional body of POST /admin/days/{tradingDay}/close. With overrideOfflinePending, the
 * OFFLINE_SALES_PENDING blockers are lifted (recent password sign-in and a reason required).
 */
public record CloseDayRequest(Boolean overrideOfflinePending, String reason) {
    public boolean overriding() {
        return Boolean.TRUE.equals(overrideOfflinePending);
    }
}
