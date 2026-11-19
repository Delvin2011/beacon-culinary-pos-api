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
import java.util.regex.Pattern;

/**
 * T0-1: the first filter of every request (registered before Spring Security by
 * {@link LoggingConfig}, so 401s and 403s are covered too).
 * <ul>
 *   <li>Sets the request id (a safe inbound X-Request-Id, otherwise a new UUID) and returns it in the
 *       X-Request-Id response header.</li>
 *   <li>Sets the MDC: requestId, tenantId, method and path (never the query string or the body).
 *       Filters further down add the user, session and device fields.</li>
 *   <li>Writes one INFO line per request (method, route, status, duration), except for skipped
 *       paths (/health polls, SSE streams) and CORS preflights.</li>
 *   <li>Always clears the MDC in finally, so nothing leaks to the next request on the thread.</li>
 * </ul>
 */
public class RequestContextFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    /** Letters, digits, dot, underscore and hyphen; 8 to 64 characters. Anything else is replaced. */
    static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> skipPaths;

    public RequestContextFilter(List<String> skipPaths) {
        this.skipPaths = List.copyOf(skipPaths);
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
        var status = failure != null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        log.atInfo()
                .addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs)
                .log("request {} {} status={} durationMs={}", request.getMethod(), route != null ? route : path,
                        status, durationMs);
    }
}
