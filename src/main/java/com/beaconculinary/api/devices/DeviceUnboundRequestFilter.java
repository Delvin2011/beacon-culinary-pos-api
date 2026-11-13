package com.beaconculinary.api.devices;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * WS1-1b decision P4: orders and shifts are not gated in this phase. In optional and required mode,
 * a CASHIER session using /orders or /shifts from a browser without a valid till registration is
 * logged with the stable marker DEVICE_UNBOUND_TILL_REQUEST, at most once per user per 5 minutes,
 * so it can be counted in the logs. Changes nothing about the request. Registered in SecurityConfig
 * after JWT authentication; not a @Component.
 */
@Slf4j
public class DeviceUnboundRequestFilter extends OncePerRequestFilter {
    static final String MARKER = "DEVICE_UNBOUND_TILL_REQUEST";
    static final Duration INTERVAL = Duration.ofMinutes(5);

    private final DeviceTrustProperties properties;
    private final Cache<Long, Boolean> recentlyLogged = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(INTERVAL).build();

    public DeviceUnboundRequestFilter(DeviceTrustProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (properties.isOff()) {
            return true;
        }
        var path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/orders") || path.startsWith("/orders/") || path.equals("/shifts") || path.startsWith("/shifts/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Long userId
                    && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_CASHIER".equals(a.getAuthority()))
                    && !DeviceContext.current().valid()
                    && recentlyLogged.asMap().putIfAbsent(userId, Boolean.TRUE) == null) {
                log.info("{} user={} method={} path={} status={}", MARKER, userId, request.getMethod(),
                        request.getRequestURI(), DeviceContext.current().status());
            }
        } catch (RuntimeException e) {
            log.error("Device trust could not check an order or shift request", e);
        }
        filterChain.doFilter(request, response);
    }
}
