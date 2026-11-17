package com.beaconculinary.api.devices;

import lombok.Data;

/** Request bodies for /admin/devices. Validated in DeviceService (field errors as a 400 map). */
public final class DeviceRequests {
    private DeviceRequests() {
    }

    @Data
    public static class Register {
        private String name;
        private String serialNumber;
        private String assetTag;
        /** Only with multi-browser enabled, after 409 DEVICE_SERIAL_EXISTS. */
        private String joinDeviceId;
    }

    @Data
    public static class Update {
        private String name;
        private String serialNumber;
        private String assetTag;
        /** O2: optional; null keeps the till's code. */
        private String tillCode;
    }

    @Data
    public static class Label {
        private String browserLabel;
    }

    @Data
    public static class Revoke {
        private String reason;
    }
}
