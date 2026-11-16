package com.beaconculinary.api.endofday;

import com.beaconculinary.api.common.AppLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Reports R3: serialises everything that decides whether a trading day is open or closed —
 * Close Day, opening a shift, and signing off or revoking — so a shift can't open while the Z is
 * being built and two Close Days can't both succeed. A SQL Server application lock keyed on the
 * day, held until the caller's transaction ends (see {@link AppLock}).
 */
@Component
public class TradingDayLock {
    private static final int TIMEOUT_MILLIS = 15_000;

    private final AppLock appLock;

    public TradingDayLock(AppLock appLock) {
        this.appLock = appLock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LocalDate tradingDay) {
        appLock.lock("trading-day:" + tradingDay, TIMEOUT_MILLIS);
    }
}
