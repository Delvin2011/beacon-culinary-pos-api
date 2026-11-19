package com.beaconculinary.api.offline;

import com.beaconculinary.api.devices.DeviceContext;
import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.sessions.SessionGuard;
import com.beaconculinary.api.sessions.SessionKind;
import com.beaconculinary.api.sessions.SessionStore;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * O3: the shared checks of the offline endpoints (contract section 3), each refusing with its own
 * code: offline enabled, a registered till (in every device-trust mode), and an active POS session
 * started on this till. The role is checked by PosSecurityRules before any of these run.
 */
@Component
public class OfflineAccess {
    private final OfflineProperties properties;
    private final SessionGuard sessionGuard;

    public OfflineAccess(OfflineProperties properties, SessionGuard sessionGuard) {
        this.properties = properties;
        this.sessionGuard = sessionGuard;
    }

    /** The till and browser behind the request's deviceToken cookie. */
    public record TillRef(UUID deviceId, UUID credentialId) {
    }

    /** 403 OFFLINE_DISABLED unless pos.offline.enabled. */
    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw OfflineException.disabled();
        }
    }

    /** 403 DEVICE_NOT_REGISTERED unless the cookie is an active browser of an active till. */
    public TillRef requireTill() {
        var context = DeviceContext.current();
        if (!context.valid() || context.deviceId() == null || context.credentialId() == null) {
            throw DeviceException.notRegistered();
        }
        return new TillRef(UUID.fromString(context.deviceId()), UUID.fromString(context.credentialId()));
    }

    /**
     * The caller's session, which must be an active POS session started on {@code deviceId}:
     * 401 SESSION_ENDED when it has ended, 403 POS_SESSION_REQUIRED for any other session.
     */
    public SessionStore.SessionRow requirePosSessionOn(UUID deviceId) {
        var session = sessionGuard.current();
        if (session == null || session.kind() != SessionKind.POS || !deviceId.equals(session.deviceId())) {
            throw OfflineException.posSessionRequired();
        }
        return session;
    }
}
