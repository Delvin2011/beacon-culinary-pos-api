package com.beaconculinary.api.common.logging;

import org.slf4j.MDC;

/**
 * T0-1: the MDC keys every log line about a request carries, in one place. In production they are
 * top-level fields of the JSON line (Logstash format); see docs/2026-11-19-logging.md.
 *
 * <p>Only {@link RequestContextFilter} clears the MDC (in its finally block). Code further down the
 * chain only adds fields, so the request log line, written last, sees all of them.
 */
public final class RequestLogContext {
    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";
    public static final String METHOD = "method";
    public static final String PATH = "path";
    public static final String ROUTE = "route";

    /** Reserved for multi-tenancy; a later step sets the real tenant. */
    public static final String DEFAULT_TENANT = "default";

    private RequestLogContext() {
    }

    static void start(String requestId, String method, String path) {
        MDC.clear();
        put(REQUEST_ID, requestId);
        put(TENANT_ID, DEFAULT_TENANT);
        put(METHOD, method);
        put(PATH, path);
    }

    static void route(String route) {
        put(ROUTE, route);
    }

    static void clear() {
        MDC.clear();
    }

    static void put(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
