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
import java.util.HexFormat;
import java.util.Locale;

/**
 * WS1-1 login protection: check the rules before a credential check, record the result after.
 *
 * <ul>
 *   <li>off: does nothing, and runs no queries.</li>
 *   <li>monitor: evaluates the rules, flags would_block and rule_triggered, logs a WARN, and always
 *       lets the request continue exactly as before. No lock is ever written; the lock that would
 *       have applied is only logged.</li>
 *   <li>enforce: refused at startup until Phase 2 ({@link LoginProtectionProperties#isModeSupported}).</li>
 * </ul>
 *
 * <p>Nothing here may break a login: any error while checking or recording is logged and ignored.
 * The PIN or password is never passed in, stored or logged.
 */
@Slf4j
@Service
public class LoginAttemptService {
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
        return new LoginDecision(true, blocking, evaluation.alert(), properties.getBlockDuration(), null);
    }

    private void logDecision(LoginAttempt attempt, Long userId, long primary, long secondary) {
        var decision = attempt.decision();
        if (decision.block()) {
            log.warn("Login protection (monitor): would block {} attempt; rule={} user={} id={} ip={} failures={}/{} {}",
                    attempt.method(), decision.rule(), userId == null ? "-" : userId, safeIdentifier(attempt),
                    attempt.request().clientIp(), primary, secondary, describeConsequence(decision));
        }
        if (decision.alert() != null) {
            log.warn("Login protection alert: rule={} method={} user={} id={} ip={} failures={}/{}",
                    decision.alert(), attempt.method(), userId == null ? "-" : userId, safeIdentifier(attempt),
                    attempt.request().clientIp(), primary, secondary);
        }
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
