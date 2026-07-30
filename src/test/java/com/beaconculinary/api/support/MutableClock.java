package com.beaconculinary.api.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A {@link Clock} whose current instant can be reassigned at runtime, so tests can pin
 * "now" to a specific time-of-day (e.g. to land inside or outside a menu item's serving
 * window) without depending on when the test actually executes.
 */
public class MutableClock extends Clock {
    private volatile Clock delegate;

    public MutableClock(Clock initial) {
        this.delegate = initial;
    }

    public void setTime(LocalTime time) {
        delegate = Clock.fixed(
            LocalDate.now(delegate).atTime(time).atZone(delegate.getZone()).toInstant(),
            delegate.getZone()
        );
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
