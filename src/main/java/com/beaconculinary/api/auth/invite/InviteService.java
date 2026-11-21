package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.auth.invite.InviteDtos.AcceptResponse;
import com.beaconculinary.api.auth.invite.InviteDtos.LookupResponse;
import com.beaconculinary.api.auth.invite.InviteDtos.SignInWith;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionStore;
import com.beaconculinary.api.staff.StaffExceptions;
import com.beaconculinary.api.staff.StaffRolePolicy;
import com.beaconculinary.api.staff.StaffRolePolicy.Requirement;
import com.beaconculinary.api.staff.StaffRolePolicy.RoleRule;
import com.beaconculinary.api.staff.StaffService;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * T0-6a: invite links (docs/2026-11-21-t0-6-invites-api-contract.md).
 *
 * <p>Public side: look up what a link sets, and accept it. Every lookup and accept is recorded by
 * login protection as method INVITE (identifier {@code invite:<id>}, never the token); a token that
 * matches nothing counts as a failure towards the per-IP rule INVITE_IP. Accepting sets the
 * credentials, clears their locks, ends every session of the person and marks the invite used, in
 * one transaction. It never signs anyone in and never re-activates a deactivated person.
 *
 * <p>The token, password and PIN are never logged or stored; only the token's SHA-256 is looked up.
 */
@Slf4j
@Service
public class InviteService {
    private final UserInviteStore store;
    private final UserRepository users;
    private final StaffService staffService;
    private final LoginAttemptService loginAttempts;
    private final SessionStore sessions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InviteService(UserInviteStore store, UserRepository users, StaffService staffService,
                         LoginAttemptService loginAttempts, SessionStore sessions, TransactionTemplate transactions,
                         Clock clock) {
        this.store = store;
        this.users = users;
        this.staffService = staffService;
        this.loginAttempts = loginAttempts;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---- public: lookup and accept ------------------------------------------------------------

    public LookupResponse lookup(String token) {
        return recorded(token, invite -> {
            var now = UtcTimestamps.nowUtc(clock);
            var target = usable(invite, now);
            var password = requirement(invite, target.rule(), true);
            var pin = requirement(invite, target.rule(), false);
            var signInWith = signInWith(target.rule());
            return new LookupResponse(target.user().getName(), target.user().getRole(), target.rule().label(),
                    invite.purpose(), password, pin, signInWith,
                    signInWith == SignInWith.PASSWORD ? target.user().getEmail() : null,
                    UtcTimestamps.toInstant(invite.expiresAt()));
        });
    }

    public AcceptResponse accept(String token, String password, String pin) {
        return recorded(token, found -> transactions.execute(status -> {
            var now = UtcTimestamps.nowUtc(clock);
            store.lockUser(found.userId());
            // Read again under the lock: a concurrent accept, revoke or reissue wins cleanly.
            var invite = store.find(found.id()).orElseThrow(InviteRuleException::noLongerValid);
            var target = usable(invite, now);
            try {
                staffService.applyInviteCredentials(target.user(), requirement(invite, target.rule(), true),
                        requirement(invite, target.rule(), false), password, pin);
            } catch (StaffExceptions.Invalid e) {
                throw InviteRuleException.credentialsInvalid(e.getErrors());
            } catch (StaffExceptions.Conflict e) {
                throw InviteRuleException.conflict(e.getCode(), e.getMessage());
            }
            if (!store.markUsed(invite.id(), now)) {
                throw InviteRuleException.used();
            }
            var ended = sessions.endAllOfUser(invite.userId(), SessionEndReason.CREDENTIALS_CHANGED, now);
            log.info("Invite accepted; invite={} user={} purpose={} credentials={} sessionsEnded={}",
                    invite.id(), invite.userId(), invite.purpose(), invite.credentials(), ended);
            var signInWith = signInWith(target.rule());
            return new AcceptResponse(signInWith, signInWith == SignInWith.PASSWORD ? target.user().getEmail() : null);
        }));
    }

    /**
     * Finds the invite by the token's hash, runs login protection (which may refuse with 429 in
     * enforce mode) and records the outcome. A malformed token is never looked up.
     */
    private <T> T recorded(String token, Function<UserInvite, T> action) {
        var invite = InviteTokens.wellFormed(token) ? store.findByTokenHash(InviteTokens.hash(token)).orElse(null) : null;
        var userId = invite == null ? null : invite.userId();
        var attempt = loginAttempts.begin(LoginMethod.INVITE, invite == null ? null : identifier(invite), userId);
        try {
            if (invite == null) {
                throw InviteRuleException.unknown();
            }
            var result = action.apply(invite);
            loginAttempts.finish(attempt, LoginOutcome.SUCCESS, userId);
            return result;
        } catch (InviteRuleException e) {
            loginAttempts.finish(attempt, e.outcome(), userId);
            throw e;
        }
    }

    /** The login_attempts identifier of an invite. */
    static String identifier(UserInvite invite) {
        return "invite:" + invite.id();
    }

    private record Target(User user, RoleRule rule) {
    }

    /** Refuses an invite that is used, revoked, expired, or no longer fits the person. */
    private Target usable(UserInvite invite, LocalDateTime now) {
        switch (invite.status(now)) {
            case USED -> throw InviteRuleException.used();
            case REVOKED -> throw InviteRuleException.noLongerValid();
            case EXPIRED -> throw InviteRuleException.expired();
            default -> {
            }
        }
        var user = users.findById(invite.userId()).orElseThrow(InviteRuleException::noLongerValid);
        var rule = StaffRolePolicy.ruleFor(user.getRole()).orElseThrow(InviteRuleException::noLongerValid);
        var credentials = invite.credentials();
        if (!user.isActive()
                || (credentials.includesPassword() && (!rule.usesPassword() || user.getEmail() == null))
                || (credentials.includesPin() && !rule.usesPin())) {
            throw InviteRuleException.noLongerValid();
        }
        return new Target(user, rule);
    }

    /**
     * What the link asks for: NONE when the invite doesn't cover the credential; REQUIRED for a
     * reset (the admin chose it); otherwise the role's own requirement.
     */
    static Requirement requirement(UserInvite invite, RoleRule rule, boolean password) {
        var included = password ? invite.credentials().includesPassword() : invite.credentials().includesPin();
        if (!included) {
            return Requirement.NONE;
        }
        if (invite.purpose() == InvitePurpose.RESET) {
            return Requirement.REQUIRED;
        }
        return password ? rule.password() : rule.pin();
    }

    private static SignInWith signInWith(RoleRule rule) {
        return rule.usesPassword() ? SignInWith.PASSWORD : SignInWith.PIN;
    }
}
