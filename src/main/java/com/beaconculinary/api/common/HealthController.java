package com.beaconculinary.api.common;

import lombok.AllArgsConstructor;
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
 */
@RestController
@AllArgsConstructor
public class HealthController {
    private final DatabaseHealthCheck databaseHealthCheck;
    private final Clock clock;

    public record HealthDto(String status, Instant serverTimeUtc) {
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        var up = databaseHealthCheck.databaseUp();
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthDto(up ? "UP" : "DOWN", clock.instant()));
    }
}
