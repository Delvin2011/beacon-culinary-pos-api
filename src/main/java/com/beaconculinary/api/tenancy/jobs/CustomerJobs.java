package com.beaconculinary.api.tenancy.jobs;

import com.beaconculinary.api.common.locale.SiteLocale;
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
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * Daily jobs run at their local time in each customer's own time zone, spread over 20 minutes ({@link #daily}).
 */
@Component
public class CustomerJobs {
    private static final Logger log = LoggerFactory.getLogger(CustomerJobs.class);

    private final boolean multi;
    private final ObjectProvider<CustomerDirectory> directory;
    private final ObjectProvider<DataSource> dataSource;
    /** T1-6: the customers the jobs skip now, by slug, with the reason (in memory: a restart logs the WARN once more). */
    private final ConcurrentHashMap<String, String> skipped = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SiteLocale siteLocale;
    /** T1-6: the local date each daily job last ran, by "job/slug" ("job" in single mode). In memory only. */
    private final ConcurrentHashMap<String, LocalDate> lastRun = new ConcurrentHashMap<>();
    /** T1-6: time zone values already reported as invalid ("slug=value"), so each is logged once. */
    private final Set<String> badZones = ConcurrentHashMap.newKeySet();

    public CustomerJobs(TenancyProperties properties, ObjectProvider<CustomerDirectory> directory,
                        ObjectProvider<DataSource> dataSource, Clock clock, SiteLocale siteLocale) {
        this.multi = properties.isMulti();
        this.directory = directory;
        this.dataSource = dataSource;
        this.clock = clock;
        this.siteLocale = siteLocale;
    }

    /** Runs the job (named for the logs) once, or once per servable customer; returns how many runs failed. */
    public int forEachActiveCustomer(String jobName, Runnable work) {
        if (!multi) {
            JobContext.run(jobName, work);
            return 0;
        }
        return forEachServed(jobName, work, customer -> true, customer -> { });
    }

    /**
     * T1-6: a daily job, called every minute by the job's own tick. Runs the work for each servable customer whose local
     * time has reached {@code time} plus its spread offset ({@link DailySchedule}) and that hasn't run it on that local
     * date. Local time: the customer's tenants.time_zone, or site.time-zone (a zone id that isn't valid is logged once
     * and the default used). Single mode: once a day at {@code time} in site.time-zone. Returns how many runs failed.
     *
     * <p>"Already ran today" is kept in memory: after a restart, a job whose time has passed runs once more that day
     * (late), and a day the app is down is missed. Every daily job must be harmless with both. A run is counted when it
     * starts, so a failing run is not retried until the next day; a skipped customer runs when it is served again.
     */
    public int daily(String jobName, LocalTime time, Runnable work) {
        DailySchedule.check(time);
        var now = clock.instant();
        if (!multi) {
            var date = DailySchedule.dueDate(now, time, siteLocale.zone(), 0, lastRun.get(jobName));
            if (date != null) {
                lastRun.put(jobName, date);
                JobContext.run(jobName, work);
            }
            return 0;
        }
        return forEachServed(jobName, work,
                customer -> dueDate(jobName, customer, now, time) != null,
                customer -> lastRun.put(jobName + "/" + customer.slug(), dueDate(jobName, customer, now, time)));
    }

    private LocalDate dueDate(String jobName, Customer customer, Instant now, LocalTime time) {
        return DailySchedule.dueDate(now, time, zoneOf(customer), DailySchedule.offsetMinutes(customer.slug()),
                lastRun.get(jobName + "/" + customer.slug()));
    }

    private ZoneId zoneOf(Customer customer) {
        var stored = customer.timeZone();
        if (stored == null || stored.isBlank()) {
            return siteLocale.zone();
        }
        try {
            return ZoneId.of(stored.trim());
        } catch (DateTimeException e) {
            if (badZones.add(customer.slug() + "=" + stored)) {
                log.atWarn().addKeyValue("customer", customer.slug())
                        .log("jobs: customer {} has time zone '{}', not a valid IANA zone id; its daily jobs use {}",
                                customer.slug(), stored, siteLocale.zone());
            }
            return siteLocale.zone();
        }
    }

    private int forEachServed(String jobName, Runnable work, Predicate<Customer> due, Consumer<Customer> starting) {
        var failed = 0;
        for (var customer : directory.getObject().listAll()) {
            if (customer.status() == CustomerStatus.ARCHIVED) {
                continue;
            }
            if (!customer.isActive()) {
                skip(customer, "it is " + customer.status());
                continue;
            }
            if (!due.test(customer)) {
                continue;
            }
            try {
                CustomerContext.runAs(CurrentCustomer.of(customer), () -> {
                    checkServed();
                    served(customer);
                    starting.accept(customer);
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
