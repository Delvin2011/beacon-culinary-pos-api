package com.beaconculinary.api.auth.protection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * WS1-1 burst limit: a token bucket per client IP on /auth/** and the manager-PIN endpoints.
 *
 * <ul>
 *   <li>enforce: an empty bucket gets HTTP 429 RATE_LIMITED with Retry-After, written here because
 *       filters run before @ControllerAdvice.</li>
 *   <li>monitor: the request continues; the would-be refusal is only logged.</li>
 *   <li>off: the filter does nothing.</li>
 * </ul>
 *
 * <p>Buckets live in a bounded cache that drops idle clients, so memory cannot grow without limit.
 * No login_attempts row is written for a burst refusal, and at most one WARN per IP per
 * warn-interval is logged. Registered in SecurityConfig before JwtAuthenticationFilter, deliberately
 * not as a @Component (which would also register it as a servlet filter). Any error inside the
 * filter lets the request through.
 */
@Slf4j
public class LoginBurstFilter extends OncePerRequestFilter {
    private final LoginProtectionProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final LongSupplier nanoTime;
    private final Cache<String, ClientBucket> buckets;

    private record ClientBucket(Bucket bucket, AtomicLong lastWarnNanos) {
    }

    public LoginBurstFilter(LoginProtectionProperties properties, ClientIpResolver clientIpResolver, ObjectMapper objectMapper) {
        this(properties, clientIpResolver, objectMapper, System::nanoTime);
    }

    LoginBurstFilter(LoginProtectionProperties properties, ClientIpResolver clientIpResolver, ObjectMapper objectMapper,
                     LongSupplier nanoTime) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.nanoTime = nanoTime;
        var burst = properties.getBurst();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(burst.getMaxTrackedClients())
                .expireAfterAccess(burst.getIdleExpiry())
                .build();
    }

    /** Only the sign-in and manager-PIN endpoints, never CORS preflight, and nothing when off. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isActive() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        var path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/auth/") || path.equals("/admin/authorize") || path.equals("/admin/authorize-session"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Duration refusedFor = null;
        String clientIp = null;
        try {
            clientIp = clientIpResolver.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
            var key = clientIp == null ? "unknown" : clientIp;
            var client = buckets.get(key, k -> new ClientBucket(newBucket(), new AtomicLong(Long.MIN_VALUE)));
            var probe = client.bucket().tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                refusedFor = Duration.ofNanos(probe.getNanosToWaitForRefill());
                warnAtMostOncePerInterval(client, key, refusedFor);
            }
        } catch (RuntimeException e) {
            log.error("Login burst limit failed; letting the request through", e);
            refusedFor = null;
        }
        if (refusedFor != null && properties.isEnforcing()) {
            writeRateLimited(response, refusedFor);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Bucket newBucket() {
        var burst = properties.getBurst();
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(burst.getCapacity()).refillGreedy(burst.getRefillTokens(), burst.getRefillPeriod()))
                .build();
    }

    private void warnAtMostOncePerInterval(ClientBucket client, String clientIp, Duration refusedFor) {
        var now = nanoTime.getAsLong();
        var last = client.lastWarnNanos().get();
        var interval = properties.getBurst().getWarnInterval().toNanos();
        if ((last == Long.MIN_VALUE || now - last >= interval) && client.lastWarnNanos().compareAndSet(last, now)) {
            log.warn("Login protection ({}): burst limit {} for ip={}; retryAfter={}",
                    properties.getMode(), properties.isEnforcing() ? "refused requests" : "would refuse requests",
                    clientIp, refusedFor);
        }
    }

    private void writeRateLimited(HttpServletResponse response, Duration retryAfter) throws IOException {
        var body = LoginProtectionErrorDto.rateLimited(retryAfter);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(body.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /** For tests: the number of client IPs currently tracked, after pending evictions. */
    long trackedClients() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
