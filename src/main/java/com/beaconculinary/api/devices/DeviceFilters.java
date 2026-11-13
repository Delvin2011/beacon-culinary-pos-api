package com.beaconculinary.api.devices;

import com.beaconculinary.api.auth.protection.ClientIpResolver;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Builds the device-trust filters for SecurityConfig. The filters themselves are not beans, so
 * Spring Boot doesn't also register them as servlet filters.
 */
@Component
public class DeviceFilters {
    private final DeviceTrustProperties properties;
    private final DeviceStore store;
    private final DeviceCookies cookies;
    private final DeviceAlerts alerts;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public DeviceFilters(DeviceTrustProperties properties, DeviceStore store, DeviceCookies cookies, DeviceAlerts alerts,
                         ClientIpResolver clientIpResolver, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.cookies = cookies;
        this.alerts = alerts;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    public DeviceResolutionFilter resolutionFilter() {
        return new DeviceResolutionFilter(properties, store, cookies, alerts, clientIpResolver, clock);
    }

    public DeviceUnboundRequestFilter unboundRequestFilter() {
        return new DeviceUnboundRequestFilter(properties);
    }
}
