package com.beaconculinary.api.tills;

import com.beaconculinary.api.devices.DeviceContext;
import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.devices.DeviceStore;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * O2: which till a request comes from, taken only from its deviceToken cookie (resolved by
 * DeviceResolutionFilter in every device-trust mode). A missing, unknown, expired or revoked cookie
 * means the unregistered till. Nothing in the request body or headers chooses the till.
 */
@Component
public class CurrentTill {
    private final DeviceStore store;
    private final TillProperties properties;

    public CurrentTill(DeviceStore store, TillProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** The till's device ID, or null for the unregistered till. No database access. */
    public UUID deviceId() {
        var context = DeviceContext.current();
        return context.valid() ? UUID.fromString(context.deviceId()) : null;
    }

    /** The till with its code and name (one lookup for a registered till). */
    public Till resolve() {
        var deviceId = deviceId();
        if (deviceId == null) {
            return Till.UNREGISTERED;
        }
        return store.findDevice(deviceId.toString())
                .map(device -> new Till(deviceId, device.tillCode(), device.name()))
                .orElse(Till.UNREGISTERED);
    }

    /** R4: refuses with 403 DEVICE_NOT_REGISTERED when pos.tills.require-registered is on and this isn't a registered till. */
    public void requireRegisteredForPos() {
        if (properties.getTills().isRequireRegistered() && deviceId() == null) {
            throw DeviceException.notRegistered();
        }
    }

    public boolean requiresRegistered() {
        return properties.getTills().isRequireRegistered();
    }
}
