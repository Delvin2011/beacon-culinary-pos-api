package com.beaconculinary.api.devices;

import com.beaconculinary.api.auth.protection.ClientIpResolver;
import com.beaconculinary.api.tills.TillProperties;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The single place that decides whether a request needs a registered POS till (rule B8). The
 * decision comes from the endpoint and the target account, never from anything the client sends.
 *
 * <ul>
 *   <li>off: nothing is gated.</li>
 *   <li>optional: nothing is refused; a gated request without a valid registration is logged.</li>
 *   <li>required: a gated request without a valid registration is refused with 403
 *       DEVICE_NOT_REGISTERED (missing, invalid, expired and revoked look the same).</li>
 * </ul>
 */
@Slf4j
@Component
public class DeviceGate {
    private final DeviceTrustProperties properties;
    private final DeviceAlerts alerts;
    private final ClientIpResolver clientIpResolver;
    private final TillProperties tillProperties;

    public DeviceGate(DeviceTrustProperties properties, DeviceAlerts alerts, ClientIpResolver clientIpResolver,
                      TillProperties tillProperties) {
        this.properties = properties;
        this.tillProperties = tillProperties;
        this.alerts = alerts;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * For requests gated for everyone (POS staff list, POS tiles, manager-PIN approval): refuses in
     * required mode without a valid registration.
     */
    public void requireRegisteredTill(String what) {
        if (properties.isOff()) {
            return;
        }
        var context = DeviceContext.current();
        if (context.valid()) {
            return;
        }
        if (properties.isRequired()) {
            alerts.refusal(clientIp());
            throw DeviceException.notRegistered();
        }
        log.info("Device trust (optional): {} without a registered till; status={} ip={}", what, context.status(), clientIp());
    }

    /**
     * PIN sign-in (B8): gated when the account is CASHIER or ADMIN, or unknown (so an unknown ID
     * looks the same as a gated role). Returns true when the attempt must be refused (required
     * mode, or O2's pos.tills.require-registered in any mode; no valid registration). In optional
     * mode it only logs.
     */
    public boolean refusesPinLogin(User user) {
        if (!isPosAccount(user)) {
            return false;
        }
        var context = DeviceContext.current();
        if (context.valid()) {
            return false;
        }
        // O2 R4: pos.tills.require-registered refuses CASHIER/ADMIN PIN sign-in in every device-trust mode.
        if (properties.isRequired() || tillProperties.getTills().isRequireRegistered()) {
            alerts.refusal(clientIp());
            alerts.alert("DEVICE_GATE_REFUSED", "method=PIN user=" + (user == null ? "-" : user.getId())
                    + " ip=" + clientIp() + " status=" + context.status());
            return true;
        }
        if (properties.isOff()) {
            return false;
        }
        log.info("Device trust (optional): POS PIN sign-in without a registered till; user={} status={} ip={}",
                user == null ? "-" : user.getId(), context.status(), clientIp());
        return false;
    }

    /** Whether admins are left out of the KITCHEN and BAR login tiles (required mode). */
    public boolean hidesAdminsOutsidePos() {
        return properties.isRequired();
    }

    /** The till to put in a PIN sign-in's token ({@code dev} claim): only in optional and required mode. */
    public String deviceIdForToken() {
        if (properties.isOff()) {
            return null;
        }
        var context = DeviceContext.current();
        return context.valid() ? context.deviceId() : null;
    }

    private static boolean isPosAccount(User user) {
        return user == null || user.getRole() == Role.CASHIER || user.getRole() == Role.ADMIN;
    }

    private String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        var request = attributes.getRequest();
        return clientIpResolver.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }
}
