package com.beaconculinary.api.tenancy.jobs;

import com.beaconculinary.api.common.logging.JobContext;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.TenancyProperties;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * T1-2: background work that touches a customer database (docs/2026-11-24-routing-data-source.md). Single mode: the
 * work runs once, as before. Multi mode: once per ACTIVE customer, each inside that customer's context (its database,
 * and its slug as the log lines' tenantId), and a failure for one customer is logged with its slug and doesn't stop the
 * others. T1-6 completes background work (timing per customer's time zone, spreading runs).
 */
@Component
public class CustomerJobs {
    private static final Logger log = LoggerFactory.getLogger(CustomerJobs.class);

    private final boolean multi;
    private final ObjectProvider<CustomerDirectory> directory;

    public CustomerJobs(TenancyProperties properties, ObjectProvider<CustomerDirectory> directory) {
        this.multi = properties.isMulti();
        this.directory = directory;
    }

    /** Runs the job (named for the logs) once, or once per ACTIVE customer; returns how many runs failed. */
    public int forEachActiveCustomer(String jobName, Runnable work) {
        if (!multi) {
            JobContext.run(jobName, work);
            return 0;
        }
        var failed = 0;
        for (var customer : directory.getObject().listActive()) {
            try {
                CustomerContext.runAs(CurrentCustomer.of(customer), () -> JobContext.run(jobName, work));
            } catch (RuntimeException e) {
                // JobContext has logged the failure with its stack trace; this names the customer in the message too.
                failed++;
                log.atError().addKeyValue("customer", customer.slug())
                        .log("job {} failed for customer {}; the other customers' runs go on", jobName, customer.slug());
            }
        }
        return failed;
    }
}
