package com.beaconculinary.api.common;

import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.routing.CustomerPools;
import com.beaconculinary.api.tenancy.web.CustomerHealthCheck;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * O1: GET /health for the till's online check and clock check. Public, and outside device trust,
 * login protection and request logging. 200 UP when the database answers within 2 seconds,
 * otherwise 503 DOWN; serverTimeUtc is always present. Never cached by the browser or a proxy.
 *
 * <p>T1-2: in multi-customer mode "the database" is the platform database, and customerPools gives
 * the customer databases' pools as counts only (open, and failing their last connection), read from
 * memory: no customer database is touched and no customer is named. Single mode's answer is unchanged.
 * T1-3: that is the answer at the backend's own addresses; at a customer's address it reports that
 * customer's database (CustomerHealthCheck), with no pool counts and no name.
 */
@RestController
public class HealthController {
    private final DatabaseHealthCheck databaseHealthCheck;
    private final Clock clock;
    private final ObjectProvider<CustomerPools> customerPools;
    private final ObjectProvider<CustomerHealthCheck> customerHealth;

    public HealthController(DatabaseHealthCheck databaseHealthCheck, Clock clock, ObjectProvider<CustomerPools> customerPools,
                            ObjectProvider<CustomerHealthCheck> customerHealth) {
        this.databaseHealthCheck = databaseHealthCheck;
        this.clock = clock;
        this.customerPools = customerPools;
        this.customerHealth = customerHealth;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthDto(String status, Instant serverTimeUtc, CustomerPoolsDto customerPools) {
    }

    public record CustomerPoolsDto(int open, int failing) {
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        var customer = CustomerContext.current();
        if (customer.isPresent()) {
            var customerUp = customerHealth.getObject().databaseUp(customer.get());
            return ResponseEntity.status(customerUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new HealthDto(customerUp ? "UP" : "DOWN", clock.instant(), null));
        }
        var up = databaseHealthCheck.databaseUp();
        var pools = customerPools.getIfAvailable();
        var poolsDto = pools == null ? null : new CustomerPoolsDto(pools.state().open(), pools.state().failing());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthDto(up ? "UP" : "DOWN", clock.instant(), poolsDto));
    }
}
