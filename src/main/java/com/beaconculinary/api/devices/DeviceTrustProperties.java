package com.beaconculinary.api.devices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * WS1-1b POS till registration settings ({@code security.device-trust.*}). Validated at startup;
 * each can be overridden by environment variable (for example SECURITY_DEVICETRUST_MODE).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "security.device-trust")
public class DeviceTrustProperties {
    /** off | optional | required. */
    @NotNull
    private DeviceTrustMode mode = DeviceTrustMode.OFF;

    /** Allow more than one browser per till (join with joinDeviceId). Needs require-serial. */
    private boolean multiBrowserEnabled = false;

    /** Serial number required when registering a till. */
    private boolean requireSerial = true;

    /** A browser not seen for this many days must be registered again. */
    @Min(1)
    private int idleExpiryDays = 90;

    /** Max-Age of the deviceToken cookie; renewed while the till is in use. */
    @Min(1)
    private int cookieMaxAgeDays = 365;

    /** At most one last-seen update and cookie renewal per browser per interval. */
    @NotNull
    private Duration lastSeenInterval = Duration.ofMinutes(5);

    /** Registering, editing and revoking need a password sign-in at most this old. */
    @NotNull
    private Duration reauthMaxAge = Duration.ofMinutes(30);

    /** DEVICE_PIN rule: failed PIN sign-ins per till across all accounts and browsers. */
    @Valid
    @NotNull
    private DevicePin devicePin = new DevicePin();

    @AssertTrue(message = "security.device-trust.multi-browser-enabled=true requires security.device-trust.require-serial=true")
    public boolean isMultiBrowserNeedsSerial() {
        return !multiBrowserEnabled || requireSerial;
    }

    @AssertTrue(message = "security.device-trust durations must be positive")
    public boolean isDurationsPositive() {
        return positive(lastSeenInterval) && positive(reauthMaxAge)
                && (devicePin == null || positive(devicePin.getWindow()));
    }

    public boolean isOff() {
        return mode == DeviceTrustMode.OFF;
    }

    public boolean isRequired() {
        return mode == DeviceTrustMode.REQUIRED;
    }

    private static boolean positive(Duration duration) {
        return duration == null || (!duration.isNegative() && !duration.isZero());
    }

    @Data
    public static class DevicePin {
        @Min(1)
        private int maxFailures = 30;

        @NotNull
        private Duration window = Duration.ofMinutes(15);
    }
}
