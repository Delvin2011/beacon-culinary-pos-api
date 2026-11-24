package com.beaconculinary.api.tenancy.context;

import com.beaconculinary.api.common.logging.RequestLogContext;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * T1-2: the current customer of this thread (docs/2026-11-24-routing-data-source.md). In multi-customer mode the
 * routing data source reads it for every new connection; single mode never reads it.
 *
 * <p>Set it only with {@link #runAs}, {@link #callAs} or {@link #open} (try-with-resources), which put the previous
 * customer back afterwards, also when the work throws. While a customer is set, log lines carry its slug as
 * {@code tenantId}.
 *
 * <p>A thread doesn't inherit it: work handed to another thread (an executor, {@code @Async}) must set it again there.
 *
 * <p>Switching to another customer while a transaction is open on this thread is refused: that transaction's
 * connection belongs to the first customer, so the work would silently go to the wrong database.
 */
public final class CustomerContext {
    private static final ThreadLocal<CurrentCustomer> CURRENT = new ThreadLocal<>();

    private CustomerContext() {
    }

    /** The current customer, if any. */
    public static Optional<CurrentCustomer> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current customer; {@link NoCurrentCustomerException} when there is none. */
    public static CurrentCustomer require() {
        var customer = CURRENT.get();
        if (customer == null) {
            throw new NoCurrentCustomerException();
        }
        return customer;
    }

    /**
     * The key in-memory customer data is kept under (caches, open streams): {@code "<id>:<slug>"} for the current
     * customer, {@code ""} when there is none (always so in single mode). Data kept under one key is never served
     * under another.
     */
    public static String scopeKey() {
        var customer = CURRENT.get();
        return customer == null ? "" : customer.id() + ":" + customer.slug();
    }

    /** Runs the work as this customer, then puts the previous customer (or none) back. */
    public static void runAs(CurrentCustomer customer, Runnable work) {
        try (var ignored = open(customer)) {
            work.run();
        }
    }

    /** Runs the work as this customer and returns its result, then puts the previous customer (or none) back. */
    public static <T> T callAs(CurrentCustomer customer, Supplier<T> work) {
        try (var ignored = open(customer)) {
            return work.get();
        }
    }

    /** Makes this customer current until the returned scope is closed (use try-with-resources). */
    public static Scope open(CurrentCustomer customer) {
        if (customer == null) {
            throw new IllegalArgumentException("customer is required");
        }
        var previous = CURRENT.get();
        if (previous != null && !previous.equals(customer)
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Can't switch from customer " + previous.slug() + " to " + customer.slug()
                    + " inside a transaction: its connection belongs to " + previous.slug());
        }
        var previousTenant = MDC.get(RequestLogContext.TENANT_ID);
        CURRENT.set(customer);
        MDC.put(RequestLogContext.TENANT_ID, customer.slug());
        return new Scope(previous, previousTenant);
    }

    /** Ends an {@link #open} scope: the previous customer and log field come back. */
    public static final class Scope implements AutoCloseable {
        private final CurrentCustomer previous;
        private final String previousTenant;
        private boolean closed;

        private Scope(CurrentCustomer previous, String previousTenant) {
            this.previous = previous;
            this.previousTenant = previousTenant;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousTenant == null) {
                MDC.remove(RequestLogContext.TENANT_ID);
            } else {
                MDC.put(RequestLogContext.TENANT_ID, previousTenant);
            }
        }
    }
}
