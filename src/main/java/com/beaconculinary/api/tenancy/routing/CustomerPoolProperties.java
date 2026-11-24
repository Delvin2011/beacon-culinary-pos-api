package com.beaconculinary.api.tenancy.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * T1-2: each customer database's connection pool in multi-customer mode (docs/2026-11-25-routing-data-source.md).
 * Small on purpose: an app instance may hold a pool for every active customer.
 */
@ConfigurationProperties(prefix = "app.tenancy.customer-pool")
public class CustomerPoolProperties {
    /** Most connections one app instance opens to one customer database. */
    private int maximumSize = 5;
    /** An idle connection is closed after this (Hikari idleTimeout; the minimum idle is 0). At least 10s. */
    private Duration idleTimeout = Duration.ofMinutes(5);
    /** A pool nobody used for this long is closed altogether (its customer gets a new one on the next use). */
    private Duration evictAfter = Duration.ofMinutes(30);
    /** How long a request waits for a connection before it fails. */
    private Duration connectionTimeout = Duration.ofSeconds(10);

    public int getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(int maximumSize) {
        this.maximumSize = maximumSize;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getEvictAfter() {
        return evictAfter;
    }

    public void setEvictAfter(Duration evictAfter) {
        this.evictAfter = evictAfter;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    /** Refuses values Hikari or this design can't work with, naming the setting. */
    public void validate() {
        if (maximumSize < 1 || maximumSize > 50) {
            throw new IllegalStateException("app.tenancy.customer-pool.maximum-size must be 1 to 50");
        }
        if (idleTimeout == null || idleTimeout.compareTo(Duration.ofSeconds(10)) < 0) {
            throw new IllegalStateException("app.tenancy.customer-pool.idle-timeout must be at least 10s");
        }
        if (evictAfter == null || evictAfter.isNegative() || evictAfter.isZero()) {
            throw new IllegalStateException("app.tenancy.customer-pool.evict-after must be positive");
        }
        if (connectionTimeout == null || connectionTimeout.compareTo(Duration.ofMillis(250)) < 0) {
            throw new IllegalStateException("app.tenancy.customer-pool.connection-timeout must be at least 250ms");
        }
    }
}
