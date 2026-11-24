package com.beaconculinary.api.common;

import com.beaconculinary.api.tenancy.routing.CustomerPools;
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
 */
@RestController
public class HealthController {
    private final DatabaseHealthCheck databaseHealthCheck;
    private final Clock clock;
    private final ObjectProvider<CustomerPools> customerPools;

    public HealthController(DatabaseHealthCheck databaseHealthCheck, Clock clock, ObjectProvider<CustomerPools> customerPools) {
        this.databaseHealthCheck = databaseHealthCheck;
        this.clock = clock;
        this.customerPools = customerPools;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthDto(String status, Instant serverTimeUtc, CustomerPoolsDto customerPools) {
    }

    public record CustomerPoolsDto(int open, int failing) {
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        var up = databaseHealthCheck.databaseUp();
        var pools = customerPools.getIfAvailable();
        var poolsDto = pools == null ? null : new CustomerPoolsDto(pools.state().open(), pools.state().failing());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthDto(up ? "UP" : "DOWN", clock.instant(), poolsDto));
    }
}
