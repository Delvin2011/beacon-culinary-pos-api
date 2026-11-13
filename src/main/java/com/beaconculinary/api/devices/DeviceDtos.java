package com.beaconculinary.api.devices;

import java.time.Instant;
import java.util.List;

/** Response bodies for /admin/devices. The device token never appears in any of them. */
public final class DeviceDtos {
    private DeviceDtos() {
    }

    /** GET /admin/devices/config. {@code mode} is "off", "optional" or "required". */
    public record Config(String mode, boolean multiBrowserEnabled, boolean requireSerial) {
    }

    public record Device(String id, String name, String type, String serialNumber, String assetTag, String status,
                         String registeredBy, Instant registeredAt, String revokedBy, Instant revokedAt,
                         String revokeReason, boolean thisDevice, List<Credential> credentials) {
    }

    /** {@code status} is ACTIVE, REVOKED, or EXPIRED (active but unused for idle-expiry-days). */
    public record Credential(String id, String browserLabel, String status, String createdBy, Instant createdAt,
                             Instant lastSeenAt, String lastSeenIp, String revokedBy, Instant revokedAt,
                             String revokeReason, boolean thisBrowser) {
    }
}
