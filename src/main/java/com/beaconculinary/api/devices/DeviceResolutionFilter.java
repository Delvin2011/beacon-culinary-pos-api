package com.beaconculinary.api.devices;

import com.beaconculinary.api.auth.protection.ClientIpResolver;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.common.logging.RequestLogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Resolves the POS till behind a request from its deviceToken cookie (WS1-1b, 3-C) and stores a
 * {@link DeviceContext} for the request. It refuses nothing; gating is done by {@link DeviceGate}.
 *
 * <ul>
 *   <li>Looks the token up by its SHA-256 hash: VALID only for an active browser, used within
 *       idle-expiry-days, of an active till. Otherwise INVALID, EXPIRED or REVOKED.</li>
 *   <li>For a VALID browser, updates last seen at most once per last-seen-interval, and re-sends
 *       the cookie with a fresh Max-Age at the same moment (sliding expiry). This also runs in off
 *       mode, so tills registered in advance don't expire.</li>
 *   <li>In optional and required mode, revoked, expired and repeated invalid tokens raise alerts.</li>
 * </ul>
 *
 * Registered in SecurityConfig after the burst limit and before JWT; not a @Component. An error
 * while resolving is logged and treated as no cookie. The token is never logged.
 */
@Slf4j
public class DeviceResolutionFilter extends OncePerRequestFilter {
    private final DeviceTrustProperties properties;
    private final DeviceStore store;
    private final DeviceCookies cookies;
    private final DeviceAlerts alerts;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public DeviceResolutionFilter(DeviceTrustProperties properties, DeviceStore store, DeviceCookies cookies,
                                  DeviceAlerts alerts, ClientIpResolver clientIpResolver, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.cookies = cookies;
        this.alerts = alerts;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    // O1: GET /health is outside device trust: no token lookup, no last-seen write, no cookie
    // renewal and no invalid-token alert for a till polling its connection.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/health".equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var token = DeviceCookies.read(request);
        var context = DeviceContext.NONE;
        if (token != null) {
            try {
                context = resolve(token, request, response);
            } catch (RuntimeException e) {
                log.error("Device trust could not resolve a device token; treating the request as unregistered", e);
                context = new DeviceContext(DeviceContext.Status.ERROR, null, null, null);
            }
        }
        request.setAttribute(DeviceContext.ATTRIBUTE, context);
        filterChain.doFilter(request, response);
    }

    private DeviceContext resolve(String token, HttpServletRequest request, HttpServletResponse response) {
        var ip = clientIpResolver.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        if (!DeviceTokens.isWellFormed(token)) {
            return invalid(ip);
        }
        var hash = DeviceTokens.hash(token);
        var found = store.findByTokenHash(hash).orElse(null);
        if (found == null || !DeviceTokens.sameHash(found.tokenHash(), hash)) {
            return invalid(ip);
        }
        // T0-1: the till behind this request, for its log lines (the id and code, never the cookie).
        RequestLogContext.device(found.deviceId(), found.tillCode());
        if (!"ACTIVE".equals(found.credentialStatus()) || !"ACTIVE".equals(found.deviceStatus())) {
            if (!properties.isOff()) {
                alerts.alert("DEVICE_REVOKED_USED", "device=" + found.deviceId() + " credential=" + found.credentialId() + " ip=" + ip);
            }
            return new DeviceContext(DeviceContext.Status.REVOKED, found.deviceId(), found.credentialId(), found.deviceName());
        }
        var now = UtcTimestamps.nowUtc(clock);
        if (found.lastUsed().isBefore(now.minus(Duration.ofDays(properties.getIdleExpiryDays())))) {
            if (!properties.isOff()) {
                alerts.alert("DEVICE_EXPIRED_USED", "device=" + found.deviceId() + " credential=" + found.credentialId() + " ip=" + ip);
            }
            return new DeviceContext(DeviceContext.Status.EXPIRED, found.deviceId(), found.credentialId(), found.deviceName());
        }
        if (store.touch(found.credentialId(), now, now.minus(properties.getLastSeenInterval()), ip)) {
            cookies.write(response, token);
        }
        return new DeviceContext(DeviceContext.Status.VALID, found.deviceId(), found.credentialId(), found.deviceName());
    }

    private DeviceContext invalid(String ip) {
        if (!properties.isOff()) {
            alerts.invalidToken(ip);
        }
        return new DeviceContext(DeviceContext.Status.INVALID, null, null, null);
    }
}
