package com.beaconculinary.api.common.logging;

import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * T0-1: the MDC for background work (scheduled jobs, the health-check thread). Every line logged
 * during a run carries jobName, a short runId and tenantId; the MDC the thread had before is put
 * back afterwards. A run that throws is logged once at ERROR and the exception is rethrown.
 */
public final class JobContext {
    public static final String JOB_NAME = "jobName";
    public static final String RUN_ID = "runId";

    private static final Logger log = LoggerFactory.getLogger(JobContext.class);

    private JobContext() {
    }

    public static void run(String jobName, Runnable job) {
        call(jobName, () -> {
            job.run();
            return null;
        });
    }

    public static <T> T call(String jobName, Supplier<T> job) {
        var previous = MDC.getCopyOfContextMap();
        MDC.put(JOB_NAME, jobName);
        MDC.put(RUN_ID, UUID.randomUUID().toString().substring(0, 8));
        // T1-2: the customer the run is for (CustomerJobs), otherwise the default.
        MDC.put(RequestLogContext.TENANT_ID, CustomerContext.current().map(CurrentCustomer::slug).orElse(RequestLogContext.DEFAULT_TENANT));
        var started = System.nanoTime();
        try {
            var result = job.get();
            log.atDebug()
                    .addKeyValue("durationMs", elapsedMs(started))
                    .log("job {} finished durationMs={}", jobName, elapsedMs(started));
            return result;
        } catch (RuntimeException | Error e) {
            log.error("job {} failed", jobName, e);
            throw e;
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
