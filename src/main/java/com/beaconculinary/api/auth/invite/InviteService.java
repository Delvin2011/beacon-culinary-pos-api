package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.auth.DemoAccountGuard;
import com.beaconculinary.api.auth.RecentPasswordCheck;
import com.beaconculinary.api.auth.invite.InviteAdminDtos.IssueRequest;
import com.beaconculinary.api.auth.invite.InviteAdminDtos.IssuedInvite;
import com.beaconculinary.api.auth.invite.InviteDtos.AcceptResponse;
import com.beaconculinary.api.auth.invite.InviteDtos.LookupResponse;
import com.beaconculinary.api.auth.invite.InviteDtos.SignInWith;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionStore;
import com.beaconculinary.api.settings.SiteSettings;
import com.beaconculinary.api.staff.StaffDto;
import com.beaconculinary.api.staff.StaffExceptions;
import com.beaconculinary.api.staff.StaffRequests;
import com.beaconculinary.api.staff.StaffRolePolicy;
import com.beaconculinary.api.staff.StaffRolePolicy.Requirement;
import com.beaconculinary.api.staff.StaffRolePolicy.RoleRule;
import com.beaconculinary.api.staff.StaffService;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
 * <p>Admin side (/admin/staff): issue a STAFF_SETUP or RESET link (replacing any open one), revoke
 * it, and create a person who sets their own credentials. A link for another ADMIN needs a recent
 * password sign-in (403 REAUTH_REQUIRED). The token is returned once and never stored.
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
    private final RecentPasswordCheck recentPasswordCheck;
    private final SiteSettings siteSettings;
    private final InviteLinks links;
    private final DemoAccountGuard demoGuard;
    private final Clock clock;

    public InviteService(UserInviteStore store, UserRepository users, StaffService staffService,
                         LoginAttemptService loginAttempts, SessionStore sessions, TransactionTemplate transactions,
                         RecentPasswordCheck recentPasswordCheck, SiteSettings siteSettings, InviteLinks links,
                         DemoAccountGuard demoGuard,
                         Clock clock) {
        this.store = store;
        this.users = users;
        this.staffService = staffService;
        this.loginAttempts = loginAttempts;
        this.sessions = sessions;
        this.transactions = transactions;
        this.recentPasswordCheck = recentPasswordCheck;
        this.siteSettings = siteSettings;
        this.links = links;
        this.demoGuard = demoGuard;
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

    // ---- admin: issue, revoke, create with invite ---------------------------------------------

    /**
     * Issues a STAFF_SETUP or RESET link for a staff member, revoking any open one (REPLACED). The
     * credentials default to every credential the role uses (STAFF_SETUP) or the ones it requires
     * (RESET). Runs in the caller's transaction when there is one.
     */
    public IssuedInvite issue(long userId, IssueRequest request) {
        var purpose = request == null ? null : request.purpose();
        if (purpose != InvitePurpose.STAFF_SETUP && purpose != InvitePurpose.RESET) {
            throw InviteRuleException.requestInvalid(java.util.Map.of("purpose", "Purpose must be STAFF_SETUP or RESET."));
        }
        var user = users.findById(userId).orElseThrow(StaffExceptions.NotFound::new);
        var adminId = currentUserId();
        if (adminId != null && adminId == userId) {
            throw InviteRuleException.self();
        }
        if (!user.isActive()) {
            throw InviteRuleException.userInactive();
        }
        if (user.isDemo()) {
            throw InviteRuleException.demoAccount();
        }
        var rule = StaffRolePolicy.ruleFor(user.getRole()).orElseThrow(StaffExceptions.NotFound::new);
        var credentials = credentialsFor(rule, purpose, request.credentials(), user);
        // Another admin's link is an admin account: same re-authentication as other sensitive actions.
        var admin = user.getRole() == Role.ADMIN
                ? recentPasswordCheck.require("send a sign-in link to another admin")
                : users.findById(adminId).orElseThrow(() -> new IllegalStateException("signed-in admin not found"));

        var token = InviteTokens.newToken();
        var now = UtcTimestamps.nowUtc(clock);
        var expiresAt = now.plusHours(siteSettings.inviteExpiryHours());
        var inviteId = transactions.execute(status -> {
            store.lockUser(userId);
            store.revokeOpen(userId, InviteRevokeReason.REPLACED, admin.getId(), admin.getName(), now);
            return store.insertByAdmin(userId, purpose, credentials, InviteTokens.hash(token), admin.getId(),
                    admin.getName(), now, expiresAt);
        });
        log.info("Invite issued; invite={} user={} purpose={} credentials={} by={} expiresAt={}",
                inviteId, userId, purpose, credentials, admin.getId(), expiresAt);
        return new IssuedInvite(inviteId, purpose, credentials, token, links.link(token),
                UtcTimestamps.toInstant(expiresAt));
    }

    /** Revokes the staff member's open link, if any. Returns whether one was open. */
    public boolean revoke(long userId) {
        users.findById(userId).orElseThrow(StaffExceptions.NotFound::new);
        var adminId = currentUserId();
        var adminName = adminId == null ? null : users.findById(adminId).map(User::getName).orElse(null);
        var now = UtcTimestamps.nowUtc(clock);
        var revoked = transactions.execute(status -> {
            store.lockUser(userId);
            return store.revokeOpen(userId, InviteRevokeReason.REVOKED, adminId, adminName, now);
        });
        if (revoked > 0) {
            log.info("Invite revoked; user={} by={}", userId, adminId);
        }
        return revoked > 0;
    }

    /**
     * POST /admin/staff with setup INVITE: creates the person without a password or PIN and issues
     * their STAFF_SETUP link, in one transaction. The response carries the link once.
     */
    @Transactional
    public StaffDto createStaffWithInvite(StaffRequests.Create request) {
        var created = staffService.create(request);
        var issued = issue(created.id(), new IssueRequest(InvitePurpose.STAFF_SETUP, null));
        return staffService.get(created.id()).withIssuedInvite(issued);
    }

    private static InviteCredentials credentialsFor(RoleRule rule, InvitePurpose purpose, InviteCredentials asked,
                                                    User user) {
        var fields = new LinkedHashMap<String, String>();
        InviteCredentials credentials;
        if (asked == null) {
            credentials = purpose == InvitePurpose.STAFF_SETUP
                    ? InviteCredentials.of(rule.usesPassword(), rule.usesPin())
                    : InviteCredentials.of(rule.password() == Requirement.REQUIRED, rule.pin() == Requirement.REQUIRED);
        } else {
            credentials = asked;
            if (asked.includesPassword() && !rule.usesPassword()) {
                fields.put("credentials", rule.label() + " accounts don't use a password.");
            } else if (asked.includesPin() && !rule.usesPin()) {
                fields.put("credentials", rule.label() + " accounts don't use a PIN.");
            }
        }
        if (fields.isEmpty() && credentials.includesPassword() && user.getEmail() == null) {
            fields.put("email", "Add an email address first: it is how they sign in with a password.");
        }
        if (!fields.isEmpty()) {
            throw InviteRuleException.requestInvalid(fields);
        }
        return credentials;
    }

    private static Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }

    // ---- shared ---------------------------------------------------------------------------------

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
        if (demoGuard.refuses(user)) {
            demoGuard.alertRefused("INVITE", user.getId());
            throw InviteRuleException.demoRefused();
        }
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
