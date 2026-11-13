package com.beaconculinary.api.staff;

import com.beaconculinary.api.auth.protection.LoginProtectionStore;
import com.beaconculinary.api.auth.protection.LoginRuleEvaluator;
import com.beaconculinary.api.common.UtcTimestamps;

import java.time.Instant;

/**
 * One sign-in method's account lock, as the staff page shows it (WS1-1).
 *
 * @param locked              sign-in by this method is locked now
 * @param lockedUntil         when the lock ends (UTC); null when not locked or when a manager must unlock it
 * @param requiresAdminUnlock the lock only ends with POST /admin/staff/{id}/unlock
 */
public record AccountLockDto(boolean locked, Instant lockedUntil, boolean requiresAdminUnlock) {
    static final AccountLockDto UNLOCKED = new AccountLockDto(false, null, false);

    static AccountLockDto of(LoginProtectionStore.ActiveLock lock) {
        if (lock == null) {
            return UNLOCKED;
        }
        var adminUnlock = lock.level() >= LoginRuleEvaluator.ADMIN_UNLOCK_LEVEL
                || !lock.lockedUntil().isBefore(LoginProtectionStore.ADMIN_UNLOCK);
        return new AccountLockDto(true, adminUnlock ? null : UtcTimestamps.toInstant(lock.lockedUntil()), adminUnlock);
    }
}
