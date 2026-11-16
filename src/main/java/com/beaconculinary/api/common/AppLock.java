package com.beaconculinary.api.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A SQL Server application lock (sp_getapplock) on a named resource, exclusive and held until the
 * caller's transaction ends. Used to serialise work that has no row to lock yet: closing a trading
 * day, and two requests carrying the same idempotency key. It goes through the EntityManager so it
 * runs on the transaction's own connection even when it's the transaction's first statement.
 */
@Component
public class AppLock {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String resource, int timeoutMillis) {
        // The driver runs with implicit transactions, which only begin at the first statement that
        // touches a table; sp_getapplock doesn't, so read a table first or, as the transaction's
        // first statement, the lock would find no transaction to belong to.
        var result = ((Number) entityManager.createNativeQuery("""
                DECLARE @result INT, @begin INT;
                SELECT TOP (0) @begin = z_number FROM z_readings;
                EXEC @result = sp_getapplock @Resource = :resource, @LockMode = 'Exclusive', @LockOwner = 'Transaction',
                                             @LockTimeout = :timeout;
                SELECT @result;""")
                .setParameter("resource", resource)
                .setParameter("timeout", timeoutMillis)
                .getSingleResult()).intValue();
        if (result < 0) {
            throw new IllegalStateException("Couldn't take lock " + resource + " (sp_getapplock " + result + ").");
        }
    }
}
