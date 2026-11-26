package com.beaconculinary.api.tenancy.jobs;

import com.beaconculinary.api.common.logging.JobContext;
import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.TenancyProperties;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.routing.CustomerNotServedException;
import com.beaconculinary.api.tenancy.routing.CustomerSchemaBehindException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T1-2: background work that touches a customer database (docs/2026-11-24-routing-data-source.md). Single mode: the
 * work runs once, as before. Multi mode: once per ACTIVE customer, each inside that customer's context (its database,
 * and its slug as the log lines' tenantId), and a failure for one customer is logged with its slug and doesn't stop the
 * others.
 *
 * <p>T1-6: a customer that can't be served now is skipped by every job: PROVISIONING, SUSPENDED or MIGRATION_FAILED
 * (seen in the directory, without connecting), or ACTIVE with a database behind the app's schema version (found by
 * taking one pooled connection before the work). It is logged once at WARN when it starts being skipped (or the reason
 * changes) and once at INFO when it is served again, not on every run. ARCHIVED customers are left out silently.
 */
@Component
public class CustomerJobs {
    private static final Logger log = LoggerFactory.getLogger(CustomerJobs.class);

    private final boolean multi;
    private final ObjectProvider<CustomerDirectory> directory;
    private final ObjectProvider<DataSource> dataSource;
    /** T1-6: the customers the jobs skip now, by slug, with the reason (in memory: a restart logs the WARN once more). */
    private final ConcurrentHashMap<String, String> skipped = new ConcurrentHashMap<>();

    public CustomerJobs(TenancyProperties properties, ObjectProvider<CustomerDirectory> directory,
                        ObjectProvider<DataSource> dataSource) {
        this.multi = properties.isMulti();
        this.directory = directory;
        this.dataSource = dataSource;
    }

    /** Runs the job (named for the logs) once, or once per servable customer; returns how many runs failed. */
    public int forEachActiveCustomer(String jobName, Runnable work) {
        if (!multi) {
            JobContext.run(jobName, work);
            return 0;
        }
        var failed = 0;
        for (var customer : directory.getObject().listAll()) {
            if (customer.status() == CustomerStatus.ARCHIVED) {
                continue;
            }
            if (!customer.isActive()) {
                skip(customer, "it is " + customer.status());
                continue;
            }
            try {
                CustomerContext.runAs(CurrentCustomer.of(customer), () -> {
                    checkServed();
                    served(customer);
                    JobContext.run(jobName, work);
                });
            } catch (CustomerSchemaBehindException e) {
                skip(customer, "its database is behind the app's schema version");
            } catch (CustomerNotServedException e) {
                skip(customer, "it is " + e.status());
            } catch (RuntimeException e) {
                // JobContext has logged the failure with its stack trace; this names the customer in the message too.
                failed++;
                log.atError().addKeyValue("customer", customer.slug())
                        .log("job {} failed for customer {}; the other customers' runs go on", jobName, customer.slug());
            }
        }
        return failed;
    }

    /** Takes and returns one pooled connection: throws CustomerNotServedException when the customer isn't served. */
    private void checkServed() {
        try (var ignored = dataSource.getObject().getConnection()) {
            // Served: the pool answered (its first connection checked the collation and the schema version).
        } catch (SQLException e) {
            // A real failure (not "not served"): an ERROR with its cause, then counted as this customer's failed run.
            log.error("jobs: the current customer's database can't be reached", e);
            throw new IllegalStateException("the customer's database can't be reached", e);
        }
    }

    private void skip(Customer customer, String reason) {
        if (!reason.equals(skipped.put(customer.slug(), reason))) {
            log.atWarn().addKeyValue("customer", customer.slug()).addKeyValue("reason", reason)
                    .log("jobs: customer {} is skipped by every job, as {} (logged once; INFO when it is served again)",
                            customer.slug(), reason);
        }
    }

    private void served(Customer customer) {
        if (skipped.remove(customer.slug()) != null) {
            log.atInfo().addKeyValue("customer", customer.slug())
                    .log("jobs: customer {} is served again; its jobs run", customer.slug());
        }
    }
}
