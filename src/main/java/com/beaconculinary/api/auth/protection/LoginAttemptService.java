package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.common.UtcTimestamps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * WS1-1 login protection: check the rules before a credential check, record the result after.
 *
 * <ul>
 *   <li>off: does nothing, and runs no queries.</li>
 *   <li>monitor: evaluates the rules, flags would_block and rule_triggered, logs a WARN, and always
 *       lets the request continue exactly as before. No lock is ever written; the lock that would
 *       have applied is only logged.</li>
 *   <li>enforce: refuses locked or blocked attempts with {@link LoginBlockedException} (HTTP 429)
 *       before any credential check, and writes account locks when a LOCK rule is reached.</li>
 * </ul>
 *
 * <p>A failure inside protection code never blocks a login: any error while checking or recording
 * is logged at ERROR and the request continues. The PIN or password is never passed in, stored or logged.
 */
@Slf4j
@Service
public class LoginAttemptService {
    /** Stable marker for log-based alerting (Azure). */
    public static final String ALERT_MARKER = "LOGIN_PROTECTION_ALERT";
    /** Lower bound for "any time" lookups. */
    private static final LocalDateTime EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final LoginProtectionProperties properties;
    private final LoginProtectionStore store;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public LoginAttemptService(LoginProtectionProperties properties, LoginProtectionStore store,
                               ClientIpResolver clientIpResolver, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    public boolean isActive() {
        return properties.isActive();
    }

    /** The normalised identifier for an email: trimmed, lower-cased, at most 255 characters. */
    public static String emailIdentifier(String email) {
        return email == null ? null : LoginRequestInfo.truncate(email.trim().toLowerCase(Locale.ROOT), 255);
    }

    /**
     * Evaluates the rules for an attempt that is about to be checked. Runs the same queries whether
     * or not the user exists, so it adds no timing difference between known and unknown users.
     *
     * @param identifier lower-cased email (PASSWORD), cashierId (PIN), or caller/user id as text
     * @param userId     the user whose state applies: the cashierId (PIN), the caller (MANAGER_PIN),
     *                   the user (CHANGE_PASSWORD, known PASSWORD users), or null
     */
    public LoginAttempt begin(LoginMethod method, String identifier, Long userId) {
        if (!properties.isActive()) {
            return LoginAttempt.inactive(method);
        }
        if (properties.isEnforcing()) {
            return beginEnforced(method, identifier, userId);
        }
        try {
            var request = currentRequest();
            var now = UtcTimestamps.nowUtc(clock);
            var lastSuccess = store.lastSuccess(userId, method);
            long primary = 0;
            long secondary = 0;
            switch (method) {
                case PASSWORD -> primary = store.countFailuresByIdentifier(method, identifier,
                        since(now, properties.getPasswordAccount().getWindow(), lastSuccess));
                case PIN -> {
                    primary = store.countFailuresByIdentifierAndIp(method, identifier, request.clientIp(),
                            since(now, properties.getPinPair().getWindow(), lastSuccess));
                    secondary = store.countFailuresByIdentifier(method, identifier,
                            since(now, properties.getPinAccountCeiling().getWindow(), lastSuccess));
                }
                case MANAGER_PIN -> {
                    primary = store.countFailuresByUser(method, userId,
                            since(now, properties.getManagerPinCaller().getWindow(), lastSuccess));
                    secondary = store.countFailures(method, now.minus(properties.getManagerPinGlobal().getWindow()));
                }
                case CHANGE_PASSWORD -> primary = store.countFailuresByUser(method, userId,
                        since(now, properties.getChangePasswordUser().getWindow(), lastSuccess));
            }
            var evaluation = LoginRuleEvaluator.evaluate(method, primary, secondary, properties);
            var decision = decide(method, identifier, evaluation, now, lastSuccess);
            var attempt = new LoginAttempt(true, method, identifier, request, decision);
            logDecision(attempt, userId, primary, secondary);
            return attempt;
        } catch (RuntimeException e) {
            log.error("Login protection check failed for {}; continuing without it", method, e);
            return LoginAttempt.inactive(method);
        }
    }

    /** Records the attempt. {@code pinNonStandard} is set only for PIN logins (decision D4 evidence). */
    public void finish(LoginAttempt attempt, LoginOutcome outcome, Long userId, Boolean pinNonStandard) {
        if (attempt == null || !attempt.active()) {
            return;
        }
        try {
            var decision = attempt.decision();
            var request = attempt.request();
            var row = new LoginProtectionStore.AttemptRow(
                    UtcTimestamps.nowUtc(clock), attempt.method(), attempt.identifier(), userId,
                    request.clientIp(), request.remoteAddr(), request.forwardedFor(), request.userAgent(),
                    outcome, outcome != LoginOutcome.VALIDATION && decision.block(),
                    outcome == LoginOutcome.VALIDATION || decision.rule() == null ? null : decision.rule().name(),
                    attempt.method() == LoginMethod.PIN && outcome == LoginOutcome.SUCCESS ? pinNonStandard : null);
            if (properties.isEnforcing() && outcome.isFailure() && reachesLock(attempt)) {
                applyAccountLock(attempt, row, userId);
                return;
            }
            store.saveAttempt(row, outcome == LoginOutcome.SUCCESS ? userId : null);
        } catch (RuntimeException e) {
            log.error("Login protection could not record a {} attempt; continuing", attempt.method(), e);
        }
    }

    public void finish(LoginAttempt attempt, LoginOutcome outcome, Long userId) {
        finish(attempt, outcome, userId, null);
    }

    /**
     * Records a request that failed validation before any credential check. It is not evaluated
     * and does not count as a failure.
     */
    public void recordValidationFailure(LoginMethod method, String identifier, Long userId) {
        if (!properties.isActive()) {
            return;
        }
        try {
            var attempt = new LoginAttempt(true, method, identifier, currentRequest(), LoginDecision.ALLOW);
            finish(attempt, LoginOutcome.VALIDATION, userId);
        } catch (RuntimeException e) {
            log.error("Login protection could not record a validation failure; continuing", e);
        }
    }

    private LoginDecision decide(LoginMethod method, String identifier, LoginRuleEvaluator.Evaluation evaluation,
                                 LocalDateTime now, LocalDateTime lastSuccess) {
        var blocking = evaluation.blocking();
        if (blocking == null) {
            return evaluation.alert() == null ? LoginDecision.ALLOW
                    : new LoginDecision(false, evaluation.alert(), evaluation.alert(), null, null);
        }
        if (blocking.effect() == LoginRule.Effect.LOCK) {
            // Escalation in monitor mode is worked out from earlier would-be locks, since none is written.
            var after = later(now.minus(properties.getEscalationReset()), lastSuccess);
            var priorLocks = store.countLockStarts(method, identifier, blocking, after);
            // If the latest attempt for this key already carried the rule, this attempt is inside
            // that same would-be lock rather than starting the next one.
            var continuing = store.latestRule(method, identifier, after).map(blocking.name()::equals).orElse(false);
            var level = continuing
                    ? (int) Math.min(LoginRuleEvaluator.ADMIN_UNLOCK_LEVEL, Math.max(1, priorLocks))
                    : LoginRuleEvaluator.nextLockLevel(priorLocks);
            return new LoginDecision(true, blocking, evaluation.alert(), LoginRuleEvaluator.lockDuration(level, properties), level);
        }
        return new LoginDecision(true, blocking, evaluation.alert(), threshold(blocking).getWindow(), null);
    }

    private void logDecision(LoginAttempt attempt, Long userId, long primary, long secondary) {
        var decision = attempt.decision();
        if (decision.block()) {
            log.warn("Login protection (monitor): would block {} attempt; rule={} user={} id={} ip={} failures={}/{} {}",
                    attempt.method(), decision.rule(), userId == null ? "-" : userId, safeIdentifier(attempt),
                    attempt.request().clientIp(), primary, secondary, describeConsequence(decision));
        }
        if (decision.alert() != null) {
            log.warn("{} type={} mode={} method={} user={} id={} ip={} failures={}/{}",
                    ALERT_MARKER, decision.alert(), properties.getMode(), attempt.method(), userId == null ? "-" : userId,
                    safeIdentifier(attempt), attempt.request().clientIp(), primary, secondary);
        }
    }

    // ---- enforce mode ----

    /**
     * Enforce mode: refuses the attempt before any credential check when an account lock is active
     * or a BLOCK rule is at its threshold. The refusal is recorded as LOCKED or RATE_LIMITED (not a
     * failure) and thrown as {@link LoginBlockedException}. Any error while evaluating fails open.
     */
    private LoginAttempt beginEnforced(LoginMethod method, String identifier, Long userId) {
        LoginAttempt attempt;
        try {
            var request = currentRequest();
            var now = UtcTimestamps.nowUtc(clock);
            var state = store.state(userId, method).orElse(null);
            // Failures before the latest success or unlock no longer count.
            var reset = state == null ? null : laterOrNull(state.lastSuccessAt(), state.unlockedAt());
            var lockRule = lockRuleFor(method);
            LoginDecision refusal = null;
            LocalDateTime lockStart = null;

            if (lockRule != null) {
                if (userId != null) {
                    // Known user: user_auth_state is the lock.
                    if (state != null && state.lockedAt(now)) {
                        refusal = lockRefusal(lockRule, state.lockLevel(), state.lockedUntil(), now);
                    }
                    lockStart = state == null ? null : state.lockedAt();
                } else {
                    // Unknown email or cashier: the lock is recorded on the attempt that triggered it.
                    var history = store.latestLock(method, identifier, EPOCH).orElse(null);
                    if (history != null && history.lockedAt(now)) {
                        refusal = lockRefusal(lockRule, history.level(), history.lockedUntil(), now);
                    }
                    lockStart = history == null ? null : history.lockedAt();
                }
            }

            long primary = 0;
            long secondary = 0;
            long lockFailures = 0;
            LoginRule alert = null;
            if (refusal == null) {
                switch (method) {
                    case PASSWORD -> {
                        // Each lock needs fresh failures: count after the latest lock start too.
                        primary = store.countFailuresByIdentifier(method, identifier,
                                latest(now.minus(properties.getPasswordAccount().getWindow()), reset, lockStart));
                        lockFailures = primary;
                    }
                    case PIN -> {
                        secondary = store.countFailuresByIdentifier(method, identifier,
                                latest(now.minus(properties.getPinAccountCeiling().getWindow()), reset, lockStart));
                        lockFailures = secondary;
                        var pairAfter = latest(now.minus(properties.getPinPair().getWindow()), reset, null);
                        primary = store.countFailuresByIdentifierAndIp(method, identifier, request.clientIp(), pairAfter);
                        if (primary >= properties.getPinPair().getMaxFailures()) {
                            var oldest = store.failureAtByIdentifierAndIp(method, identifier, request.clientIp(), pairAfter,
                                    primary - properties.getPinPair().getMaxFailures());
                            refusal = blockRefusal(LoginRule.PIN_PAIR, oldest.orElse(null), now);
                        }
                    }
                    case MANAGER_PIN -> {
                        var callerAfter = latest(now.minus(properties.getManagerPinCaller().getWindow()), reset, null);
                        primary = store.countFailuresByUser(method, userId, callerAfter);
                        secondary = store.countFailures(method, now.minus(properties.getManagerPinGlobal().getWindow()));
                        if (secondary >= properties.getManagerPinGlobal().getMaxFailures()) {
                            alert = LoginRule.MANAGER_PIN_GLOBAL;
                        }
                        if (primary >= properties.getManagerPinCaller().getMaxFailures()) {
                            var oldest = store.failureAtByUser(method, userId, callerAfter,
                                    primary - properties.getManagerPinCaller().getMaxFailures());
                            refusal = blockRefusal(LoginRule.MANAGER_PIN_CALLER, oldest.orElse(null), now);
                        }
                    }
                    case CHANGE_PASSWORD -> {
                        var userAfter = latest(now.minus(properties.getChangePasswordUser().getWindow()), reset, null);
                        primary = store.countFailuresByUser(method, userId, userAfter);
                        if (primary >= properties.getChangePasswordUser().getMaxFailures()) {
                            var oldest = store.failureAtByUser(method, userId, userAfter,
                                    primary - properties.getChangePasswordUser().getMaxFailures());
                            refusal = blockRefusal(LoginRule.CHANGE_PASSWORD_USER, oldest.orElse(null), now);
                        }
                    }
                }
            }

            var decision = refusal != null ? refusal
                    : alert != null ? new LoginDecision(false, alert, alert, null, null) : LoginDecision.ALLOW;
            if (alert != null) {
                decision = new LoginDecision(decision.block(), decision.rule(), alert, decision.retryAfter(), decision.wouldLockLevel());
            }
            attempt = new LoginAttempt(true, method, identifier, request, decision, lockRule, lockFailures);
            if (alert != null) {
                log.warn("{} type={} mode={} method={} user={} id={} ip={} failures={}/{}",
                        ALERT_MARKER, alert, properties.getMode(), method, userId == null ? "-" : userId,
                        safeIdentifier(attempt), request.clientIp(), primary, secondary);
            }
        } catch (RuntimeException e) {
            log.error("Login protection check failed for {}; continuing without it", method, e);
            return LoginAttempt.inactive(method);
        }
        if (attempt.decision().block()) {
            recordRefusal(attempt, userId);
            throw new LoginBlockedException(attempt.decision().rule(), attempt.decision().retryAfter());
        }
        return attempt;
    }

    private LoginDecision lockRefusal(LoginRule rule, int level, LocalDateTime lockedUntil, LocalDateTime now) {
        var adminUnlock = level >= LoginRuleEvaluator.ADMIN_UNLOCK_LEVEL || !lockedUntil.isBefore(LoginProtectionStore.ADMIN_UNLOCK);
        return new LoginDecision(true, rule, null, adminUnlock ? null : Duration.between(now, lockedUntil), level);
    }

    /** A window block ends when the failure at {@code oldestCounted} leaves the window. */
    private LoginDecision blockRefusal(LoginRule rule, LocalDateTime oldestCounted, LocalDateTime now) {
        var window = threshold(rule).getWindow();
        var retryAfter = oldestCounted == null ? window : Duration.between(now, oldestCounted.plus(window));
        return new LoginDecision(true, rule, null, retryAfter.isNegative() ? Duration.ofSeconds(1) : retryAfter, null);
    }

    private void recordRefusal(LoginAttempt attempt, Long userId) {
        try {
            var decision = attempt.decision();
            var request = attempt.request();
            var outcome = decision.rule().effect() == LoginRule.Effect.LOCK ? LoginOutcome.LOCKED : LoginOutcome.RATE_LIMITED;
            store.saveAttempt(new LoginProtectionStore.AttemptRow(
                    UtcTimestamps.nowUtc(clock), attempt.method(), attempt.identifier(), userId,
                    request.clientIp(), request.remoteAddr(), request.forwardedFor(), request.userAgent(),
                    outcome, true, decision.rule().name(), null), null);
            log.info("Login protection: refused {} attempt; rule={} user={} id={} ip={} retryAfter={}",
                    attempt.method(), decision.rule(), userId == null ? "-" : userId, safeIdentifier(attempt),
                    request.clientIp(), decision.retryAfter() == null ? "admin unlock" : decision.retryAfter());
        } catch (RuntimeException e) {
            log.error("Login protection could not record a refused {} attempt", attempt.method(), e);
        }
    }

    /** Whether this failure brings the method's LOCK rule to its threshold. */
    private boolean reachesLock(LoginAttempt attempt) {
        return attempt.lockRule() != null && attempt.lockFailures() + 1 >= threshold(attempt.lockRule()).getMaxFailures();
    }

    /**
     * Writes the failure that reaches a LOCK rule's threshold together with the lock: in
     * user_auth_state for a known user, or on the attempt row for an unknown identifier. Both
     * escalate the same way (15 min, 60 min, then admin unlock; back to 15 min after 24 h).
     */
    private void applyAccountLock(LoginAttempt attempt, LoginProtectionStore.AttemptRow failure, Long userId) {
        var rule = attempt.lockRule();
        var row = new LoginProtectionStore.AttemptRow(failure.attemptedAt(), failure.method(), failure.identifier(), userId,
                failure.clientIp(), failure.remoteAddr(), failure.forwardedFor(), failure.userAgent(),
                failure.outcome(), false, rule.name(), null);
        LoginProtectionStore.HistoryLock lock;
        if (userId != null) {
            lock = store.saveAttemptWithAccountLock(row, userId, properties.getEscalationReset(), properties.getLockDurations());
        } else {
            var now = row.attemptedAt();
            var prior = store.latestLock(attempt.method(), attempt.identifier(), now.minus(properties.getEscalationReset()));
            var level = prior.map(p -> LoginRuleEvaluator.nextLockLevel(p.level())).orElse(1);
            var duration = LoginRuleEvaluator.lockDuration(level, properties);
            var until = duration == null ? LoginProtectionStore.ADMIN_UNLOCK : now.plus(duration);
            store.saveAttempt(row.withLock(level, until), null);
            lock = new LoginProtectionStore.HistoryLock(level, until, now);
        }
        if (lock.level() > 0) {
            log.warn("Login protection: locked {} sign-in; rule={} level={} user={} id={} ip={} until={}",
                    attempt.method(), rule, lock.level(), userId == null ? "-" : userId, safeIdentifier(attempt),
                    attempt.request().clientIp(), lock.level() >= LoginRuleEvaluator.ADMIN_UNLOCK_LEVEL ? "admin unlock" : lock.lockedUntil());
            if (rule.alerts()) {
                log.warn("{} type={} mode={} method={} user={} id={} ip={} level={}", ALERT_MARKER, rule, properties.getMode(),
                        attempt.method(), userId == null ? "-" : userId, safeIdentifier(attempt), attempt.request().clientIp(), lock.level());
            }
            if (lock.level() >= LoginRuleEvaluator.ADMIN_UNLOCK_LEVEL) {
                log.warn("{} type=ADMIN_UNLOCK_REQUIRED mode={} rule={} method={} user={} id={} ip={}", ALERT_MARKER,
                        properties.getMode(), rule, attempt.method(), userId == null ? "-" : userId, safeIdentifier(attempt),
                        attempt.request().clientIp());
            }
        }
    }

    // ---- account state used outside sign-in ----

    /**
     * Enforce mode: whether the user has an active PASSWORD or PIN account lock (refresh is then
     * refused). Always false in monitor and off mode. Fails open.
     */
    public boolean isAccountLocked(Long userId) {
        if (!properties.isEnforcing() || userId == null) {
            return false;
        }
        try {
            return store.isAccountLocked(userId, UtcTimestamps.nowUtc(clock));
        } catch (RuntimeException e) {
            log.error("Login protection could not check the lock state for refresh; continuing", e);
            return false;
        }
    }

    /**
     * Ends locks and window blocks for these scopes (admin unlock, or a new PIN or password). Runs in
     * the caller's transaction; works in every mode. Logs an alert.
     */
    public void unlock(Long userId, Collection<LoginMethod> scopes, Long unlockedBy, String reason) {
        var wereLocked = store.unlock(userId, scopes, unlockedBy, UtcTimestamps.nowUtc(clock));
        log.warn("{} type=UNLOCK reason={} user={} scopes={} by={} wereLocked={}", ALERT_MARKER, reason, userId,
                scopes, unlockedBy == null ? "-" : unlockedBy, wereLocked);
    }

    /** Active PASSWORD and PIN locks by user, for the staff page. Empty on error. */
    public Map<Long, Map<LoginMethod, LoginProtectionStore.ActiveLock>> activeAccountLocks() {
        try {
            var result = new HashMap<Long, Map<LoginMethod, LoginProtectionStore.ActiveLock>>();
            for (var lock : store.activeAccountLocks(UtcTimestamps.nowUtc(clock))) {
                result.computeIfAbsent(lock.userId(), id -> new EnumMap<>(LoginMethod.class)).put(lock.scope(), lock);
            }
            return result;
        } catch (RuntimeException e) {
            log.error("Login protection could not read lock state", e);
            return Map.of();
        }
    }

    private static LoginRule lockRuleFor(LoginMethod method) {
        return switch (method) {
            case PASSWORD -> LoginRule.PASSWORD_ACCOUNT;
            case PIN -> LoginRule.PIN_ACCOUNT_CEILING;
            default -> null;
        };
    }

    private LoginProtectionProperties.Threshold threshold(LoginRule rule) {
        return switch (rule) {
            case PASSWORD_ACCOUNT -> properties.getPasswordAccount();
            case PIN_PAIR -> properties.getPinPair();
            case PIN_ACCOUNT_CEILING -> properties.getPinAccountCeiling();
            case MANAGER_PIN_CALLER -> properties.getManagerPinCaller();
            case MANAGER_PIN_GLOBAL -> properties.getManagerPinGlobal();
            case CHANGE_PASSWORD_USER -> properties.getChangePasswordUser();
        };
    }

    /** The latest of the times given; nulls are ignored, the first is never null. */
    private static LocalDateTime latest(LocalDateTime first, LocalDateTime second, LocalDateTime third) {
        return later(later(first, second), third);
    }

    /** The later of two times, either of which may be null; null only when both are. */
    private static LocalDateTime laterOrNull(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return later(a, b);
    }

    private static String describeConsequence(LoginDecision decision) {
        if (decision.wouldLockLevel() == null) {
            return "wouldBlockFor=" + decision.retryAfter();
        }
        return decision.retryAfter() == null
                ? "wouldLock=level " + decision.wouldLockLevel() + " (until admin unlock)"
                : "wouldLock=level " + decision.wouldLockLevel() + " for " + decision.retryAfter();
    }

    /** Emails are logged only as a short hash; numeric ids are logged as they are. */
    private static String safeIdentifier(LoginAttempt attempt) {
        var identifier = attempt.identifier();
        if (identifier == null || attempt.method() != LoginMethod.PASSWORD) {
            return identifier == null ? "-" : identifier;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "?";
        }
    }

    private LoginRequestInfo currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return LoginRequestInfo.NONE;
        }
        var request = attributes.getRequest();
        var remoteAddr = request.getRemoteAddr();
        var forwardedFor = request.getHeader("X-Forwarded-For");
        return new LoginRequestInfo(
                clientIpResolver.resolve(remoteAddr, forwardedFor),
                LoginRequestInfo.truncate(remoteAddr, ClientIpResolver.MAX_IP_LENGTH),
                LoginRequestInfo.truncate(forwardedFor, LoginRequestInfo.MAX_FORWARDED_FOR),
                LoginRequestInfo.truncate(request.getHeader("User-Agent"), LoginRequestInfo.MAX_USER_AGENT));
    }

    private static LocalDateTime since(LocalDateTime now, Duration window, LocalDateTime lastSuccess) {
        return later(now.minus(window), lastSuccess);
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return b == null || a.isAfter(b) ? a : b;
    }
}
