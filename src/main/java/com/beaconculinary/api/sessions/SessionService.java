package com.beaconculinary.api.sessions;

import com.beaconculinary.api.auth.SignIn;
import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.devices.DeviceContext;
import com.beaconculinary.api.shifts.OpenShiftConflicts;
import com.beaconculinary.api.tills.CurrentTill;
import com.beaconculinary.api.tills.TillException;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * O2 R7-R9: sessions. Every sign-in starts one; its ID goes into the tokens as {@code sid}.
 *
 * <p>A POS sign-in (section 5 of docs/2026-11-17-o2-tills-shifts-api-contract.md), after the PIN or
 * password has been checked:
 * <ol start="2">
 *   <li>refused with SHIFT_OPEN_ON_OTHER_TILL when the user has an open shift on another till (the
 *       unregistered till counts as a till), unless they're an ADMIN;</li>
 *   <li>on a registered till with an open shift, refused with TILL_LOCKED unless the user owns it or
 *       is an ADMIN (the unregistered till has no such rule);</li>
 *   <li>otherwise the user's other active POS sessions end, and so does any other active POS session
 *       on this till (REPLACED). The till's shift stays open.</li>
 * </ol>
 * STATION and BACK_OFFICE sign-ins skip these rules and never end other sessions.
 */
@Service
public class SessionService {
    private static final int SIGN_IN_LOCK_TIMEOUT_MILLIS = 15_000;

    private final SessionStore store;
    private final CurrentTill currentTill;
    private final OpenShiftConflicts openShifts;
    private final AppLock appLock;
    private final Clock clock;

    public SessionService(SessionStore store, CurrentTill currentTill, OpenShiftConflicts openShifts, AppLock appLock,
                          Clock clock) {
        this.store = store;
        this.currentTill = currentTill;
        this.openShifts = openShifts;
        this.appLock = appLock;
        this.clock = clock;
    }

    /**
     * Starts the session for a successful sign-in ({@code method} is SignIn.PASSWORD or SignIn.PIN) and
     * returns its ID. Throws TillException (409) when a POS sign-in is refused by the till rules.
     */
    @Transactional
    public UUID start(User user, String method) {
        var deviceId = currentTill.deviceId();
        var context = DeviceContext.current();
        var credentialId = context.valid() && context.credentialId() != null ? UUID.fromString(context.credentialId()) : null;
        var kind = kindOf(user, method, deviceId);
        var now = UtcTimestamps.nowUtc(clock);
        if (kind == SessionKind.POS) {
            // Two sign-ins of one user at once mustn't both survive the "one POS session" rule.
            appLock.lock("pos-sign-in:" + user.getId(), SIGN_IN_LOCK_TIMEOUT_MILLIS);
            refuseByTillRules(user, deviceId);
            store.endActivePosOfUser(user.getId(), SessionEndReason.REPLACED, now);
            if (deviceId != null) {
                store.endActivePosOnDevice(deviceId, SessionEndReason.REPLACED, now);
            }
        }
        var id = UUID.randomUUID();
        store.insert(id, user.getId(), deviceId, credentialId, kind, method, now);
        return id;
    }

    /** The session behind {@code sid}, which must be active; otherwise 401 SESSION_ENDED with its reason. */
    public SessionStore.SessionRow requireActive(String sid) {
        var id = parse(sid);
        if (id == null) {
            throw new SessionEndedException(SessionEndReason.EXPIRED);
        }
        var session = store.find(id).orElseThrow(() -> new SessionEndedException(SessionEndReason.EXPIRED));
        if (!session.active()) {
            throw new SessionEndedException(session.endReason());
        }
        return session;
    }

    /** Ends the session behind {@code sid}, if it's still active. Unknown or malformed IDs are ignored. */
    public void end(String sid, SessionEndReason reason) {
        var id = parse(sid);
        if (id != null) {
            store.end(id, reason, UtcTimestamps.nowUtc(clock));
        }
    }

    static SessionKind kindOf(User user, String method, UUID deviceId) {
        var posRole = user.getRole() == Role.CASHIER || user.getRole() == Role.ADMIN;
        if (SignIn.PIN.equals(method)) {
            return posRole ? SessionKind.POS : SessionKind.STATION;
        }
        return deviceId != null ? SessionKind.POS : SessionKind.BACK_OFFICE;
    }

    private void refuseByTillRules(User user, UUID deviceId) {
        var admin = user.getRole() == Role.ADMIN;
        if (!admin) {
            openShifts.ofCashier(user.getId())
                    .filter(own -> !Objects.equals(own.deviceId(), deviceId))
                    .ifPresent(own -> {
                        throw TillException.shiftOpenOnOtherTill(own.till(), "Sign in on that till, or ask a manager to close it.");
                    });
        }
        if (deviceId != null && !admin) {
            openShifts.onTill(deviceId)
                    .filter(shift -> shift.cashierId() != user.getId())
                    .ifPresent(shift -> {
                        throw TillException.tillLocked(shift.ownerName(), shift.tillCode());
                    });
        }
    }

    private static UUID parse(String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
