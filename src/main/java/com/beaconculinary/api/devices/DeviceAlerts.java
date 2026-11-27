package com.beaconculinary.api.devices;

import com.beaconculinary.api.common.logging.LogMarkers;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOGIN_PROTECTION_ALERT lines for device trust. Repeated events from one address (invalid tokens,
 * DEVICE_NOT_REGISTERED refusals) raise one alert per address per window once a threshold is
 * reached; counters live in a bounded, expiring cache. T1-10: counted per address at each customer, so an alert logged
 * for one customer counts only that customer's events. The device token is never logged.
 */
@Slf4j
@Component
public class DeviceAlerts {
    static final String MARKER = "LOGIN_PROTECTION_ALERT";
    static final int REPEAT_THRESHOLD = 5;
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);

    private final Cache<String, AtomicInteger> invalidTokens = counter();
    private final Cache<String, AtomicInteger> refusals = counter();

    private static Cache<String, AtomicInteger> counter() {
        return Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(REPEAT_WINDOW).build();
    }

    public void alert(String type, String details) {
        log.warn(LogMarkers.LOGIN_PROTECTION_ALERT, "{} type={} {}", MARKER, type, details);
    }

    /** An invalid device token from this address; alerts once the threshold is reached in the window. */
    public void invalidToken(String clientIp) {
        repeated(invalidTokens, "DEVICE_INVALID_TOKENS", clientIp);
    }

    /** A DEVICE_NOT_REGISTERED refusal for this address; alerts once the threshold is reached. */
    public void refusal(String clientIp) {
        repeated(refusals, "DEVICE_NOT_REGISTERED_REPEATED", clientIp);
    }

    private void repeated(Cache<String, AtomicInteger> cache, String type, String clientIp) {
        var key = clientIp == null ? "unknown" : clientIp;
        var count = cache.get(CustomerContext.scopeKey() + "|" + key, k -> new AtomicInteger()).incrementAndGet();
        if (count == REPEAT_THRESHOLD) {
            alert(type, "ip=" + key + " count=" + count + " window=" + REPEAT_WINDOW);
        }
    }
}
