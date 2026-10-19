package com.beaconculinary.api.endofday;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Reports R3: serialises everything that decides whether a trading day is open or closed —
 * Close Day, opening a shift, and signing off or revoking — so a shift can't open while the Z is
 * being built and two Close Days can't both succeed. A SQL Server application lock keyed on the
 * day, held until the caller's transaction ends. It goes through the EntityManager so it runs on
 * the transaction's own connection even when it's the transaction's first statement.
 */
@Component
public class TradingDayLock {
    private static final int TIMEOUT_MILLIS = 15_000;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LocalDate tradingDay) {
        // The driver runs with implicit transactions, which only begin at the first statement that
        // touches a table; sp_getapplock doesn't, so read a table first or, as the transaction's
        // first statement, the lock would find no transaction to belong to.
        var result = ((Number) entityManager.createNativeQuery("""
                DECLARE @result INT, @begin INT;
                SELECT TOP (0) @begin = z_number FROM z_readings;
                EXEC @result = sp_getapplock @Resource = :resource, @LockMode = 'Exclusive', @LockOwner = 'Transaction',
                                             @LockTimeout = :timeout;
                SELECT @result;""")
                .setParameter("resource", "trading-day:" + tradingDay)
                .setParameter("timeout", TIMEOUT_MILLIS)
                .getSingleResult()).intValue();
        if (result < 0) {
            throw new IllegalStateException("Couldn't lock trading day " + tradingDay + " (sp_getapplock " + result + ").");
        }
    }
}
