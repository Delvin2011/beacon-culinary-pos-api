package com.beaconculinary.api.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * T0-1: the first filter of every request (registered before Spring Security by
 * {@link LoggingConfig}, so 401s and 403s are covered too).
 * <ul>
 *   <li>Sets the request id (a safe inbound X-Request-Id, otherwise a new UUID) and returns it in the
 *       X-Request-Id response header.</li>
 *   <li>Sets the MDC: requestId, tenantId, method and path (never the query string or the body).
 *       Filters further down add the user, session and device fields.</li>
 *   <li>Writes one INFO line per request (method, route, status, duration, host), except for skipped
 *       paths (/health polls, SSE streams) and CORS preflights.</li>
 *   <li>Always clears the MDC in finally, so nothing leaks to the next request on the thread.</li>
 * </ul>
 *
 * <p>T1-L0: the host is the address the browser used. Behind a proxy that is X-Forwarded-Host, believed only
 * when the direct peer is a trusted proxy (security.login-protection.trusted-proxies, as for X-Forwarded-For);
 * otherwise the Host header. A value that isn't a plain host name (with an optional port) is logged as
 * "invalid", so a client can't write arbitrary text into the log.
 */
public class RequestContextFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    /** Letters, digits, dot, underscore and hyphen; 8 to 64 characters. Anything else is replaced. */
    static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    /** A host name or IPv4 address with an optional port; at most 253 + 6 characters. */
    static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    static final String FORWARDED_HOST = "X-Forwarded-Host";
    /** T1-3: the request's customer slug, left by the customer filter for the request line's tenantId. */
    public static final String TENANT_ATTRIBUTE = "pos.tenantId";

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> skipPaths;
    private final Predicate<String> trustedProxy;

    /** @param trustedProxy true for a peer address whose X-Forwarded-Host is believed */
    public RequestContextFilter(List<String> skipPaths, Predicate<String> trustedProxy) {
        this.skipPaths = List.copyOf(skipPaths);
        this.trustedProxy = trustedProxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var requestId = requestId(request.getHeader(HEADER));
        var path = request.getRequestURI().substring(request.getContextPath().length());
        response.setHeader(HEADER, requestId);
        RequestLogContext.start(requestId, request.getMethod(), path);

        var started = System.nanoTime();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                if (shouldLog(request, path)) {
                    logRequest(request, response, path, started, failure);
                }
            } finally {
                RequestLogContext.clear();
            }
        }
    }

    static String requestId(String inbound) {
        if (inbound != null && SAFE_REQUEST_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }

    /** The host the browser used: X-Forwarded-Host from a trusted proxy, otherwise Host; "invalid" if unsafe. */
    String host(HttpServletRequest request) {
        var host = rawHost(request, trustedProxy);
        if (host == null || host.isEmpty()) {
            return "-";
        }
        return SAFE_HOST.matcher(host).matches() ? host.toLowerCase(java.util.Locale.ROOT) : "invalid";
    }

    /**
     * T1-3: the host as sent, unchecked (null when none): X-Forwarded-Host from a trusted proxy, otherwise Host. Shared
     * with the customer filter, so the customer and the logged host always come from the same header.
     */
    public static String rawHost(HttpServletRequest request, Predicate<String> trustedProxy) {
        var forwarded = request.getHeader(FORWARDED_HOST);
        if (forwarded != null && !forwarded.isBlank() && trustedProxy.test(request.getRemoteAddr())) {
            // A proxy chain may append: the left-most entry is the host the browser used.
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getHeader("Host");
    }

    private boolean shouldLog(HttpServletRequest request, String path) {
        if ("OPTIONS".equals(request.getMethod())) {
            return false;
        }
        return skipPaths.stream().noneMatch(pattern -> MATCHER.match(pattern, path));
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response, String path, long started,
                            Throwable failure) {
        var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String r ? r : null;
        RequestLogContext.route(route);
        if (request.getAttribute(TENANT_ATTRIBUTE) instanceof String slug) {
            // T1-3: the customer filter has closed its scope by now, which put "default" back.
            RequestLogContext.put(RequestLogContext.TENANT_ID, slug);
        }
        var status = failure != null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var host = host(request);
        log.atInfo()
                .addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("host", host)
                .log("request {} {} status={} durationMs={} host={}", request.getMethod(),
                        route != null ? route : path, status, durationMs, host);
    }
}
