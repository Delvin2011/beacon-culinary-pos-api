package com.beaconculinary.api.staff;

import com.beaconculinary.api.auth.invite.InviteAdminDtos.InviteSummary;
import com.beaconculinary.api.auth.invite.InviteRevokeReason;
import com.beaconculinary.api.auth.invite.UserInvite;
import com.beaconculinary.api.auth.invite.UserInviteStore;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginProtectionStore;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.staff.StaffRolePolicy.Requirement;
import com.beaconculinary.api.staff.StaffRolePolicy.RoleRule;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Staff management (ADMIN only): create staff with the credentials their role uses, change role,
 * deactivate, and reset passwords and PINs. Credentials are stored hashed and never returned.
 *
 * <p>Rules: password 6–25 characters; PIN exactly 6 digits; email (for password roles) unique and
 * stored lowercase; admin PINs unique among all admins, because manager approval matches the
 * entered PIN against every admin. Staff are deactivated, never deleted, and the last active admin
 * can't be deactivated or moved to another role.
 */
@Service
@AllArgsConstructor
public class StaffService {
    private static final Pattern PIN = Pattern.compile("\\d{6}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final OfflineGrantStore offlineGrants;
    private final Clock clock;
    private final UserInviteStore inviteStore;

    public List<RoleRule> roles() {
        return StaffRolePolicy.RULES;
    }

    @Transactional(readOnly = true)
    public List<StaffDto> list() {
        var locks = loginAttempts.activeAccountLocks();
        var blocks = loginAttempts.activeBlocks();
        var all = userRepository.findAll();
        var invites = inviteStore.latestForUsers(all.stream().map(User::getId).toList());
        var now = UtcTimestamps.nowUtc(clock);
        return all.stream()
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> toDto(user, locks, blocks, invites.get(user.getId()), now))
                .toList();
    }

    /**
     * WS1-1: ends sign-in locks and short-term blocks for one scope, or for all when none is given.
     * Runs in this transaction (see LoginProtectionStore.unlock).
     */
    @Transactional
    public StaffDto unlock(Long id, StaffRequests.Unlock request) {
        var user = load(id);
        if (isCurrentUser(user)) {
            loginAttempts.selfUnlockRefused(user.getId(), "ADMIN_UNLOCK");
            throw new StaffExceptions.Conflict("SELF_UNLOCK",
                    "You can't unlock your own sign-in. Ask another admin to do it.");
        }
        var scope = request == null ? null : request.getScope();
        EnumSet<LoginMethod> scopes;
        if (blank(scope)) {
            scopes = LoginMethod.accountScopes();
        } else {
            var match = LoginMethod.accountScopes().stream().filter(m -> m.name().equals(scope.trim())).findFirst();
            if (match.isEmpty()) {
                throw new StaffExceptions.Invalid(Map.of("scope",
                        "Scope must be one of PASSWORD, PIN, MANAGER_PIN, CHANGE_PASSWORD."));
            }
            scopes = EnumSet.of(match.get());
        }
        loginAttempts.unlock(user.getId(), scopes, currentUserId(), "ADMIN_UNLOCK");
        return toDto(user);
    }

    @Transactional(readOnly = true)
    public StaffDto get(Long id) {
        return toDto(load(id));
    }

    @Transactional
    public StaffDto create(StaffRequests.Create request) {
        var errors = new LinkedHashMap<String, String>();
        var name = requireName(request.getName(), errors);
        // T0-6a: with setup INVITE the person sets their own credentials from a link (InviteService).
        var inviting = request.getSetup() == StaffRequests.Setup.INVITE;
        var rule = ruleFor(request.getRole(), errors);
        String email = null;
        if (rule != null) {
            email = checkEmail(rule, request.getEmail(), true, errors);
            if (inviting) {
                if (!blank(request.getPassword())) {
                    errors.put("password", "Leave the password out: the person sets it from their link.");
                }
                if (!blank(request.getPin())) {
                    errors.put("pin", "Leave the PIN out: the person sets it from their link.");
                }
            } else {
                checkCredential("password", rule.password(), request.getPassword(), true, rule, errors);
                checkCredential("pin", rule.pin(), request.getPin(), true, rule, errors);
            }
        }
        throwIfAny(errors);

        requireUniqueEmail(email, null);
        if (rule.role() == Role.ADMIN && !inviting) {
            requireUniqueAdminPin(request.getPin(), null);
        }

        var user = new User();
        user.setName(name);
        user.setRole(rule.role());
        user.setEmail(email);
        user.setPassword(blank(request.getPassword()) ? null : passwordEncoder.encode(request.getPassword()));
        user.setPinHash(blank(request.getPin()) ? null : passwordEncoder.encode(request.getPin()));
        user.setActive(true);
        userRepository.save(user);
        return toDto(user);
    }

    /**
     * Updates name, role, email and active. Moving to a role that doesn't use a password clears
     * the email and password; moving to one without a PIN clears the PIN. A credential the new
     * role requires but the person lacks must be supplied in the same request, and promoting
     * someone to ADMIN always needs a PIN (so its uniqueness can be checked).
     */
    @Transactional
    public StaffDto update(Long id, StaffRequests.Update request) {
        var user = load(id);
        var errors = new LinkedHashMap<String, String>();
        var name = requireName(request.getName(), errors);
        var rule = ruleFor(request.getRole(), errors);
        var becomingAdmin = rule != null && rule.role() == Role.ADMIN && user.getRole() != Role.ADMIN;
        // T0-6a: a credential the person will set from their open link isn't missing, unless the role changes
        // (which revokes the link).
        var awaitingLink = rule != null && rule.role() == user.getRole() && inviteStore.openForUser(user.getId()).isPresent();

        String email = null;
        if (rule != null) {
            var emailInput = request.getEmail() != null ? request.getEmail() : user.getEmail();
            email = checkEmail(rule, rule.usesPassword() ? emailInput : request.getEmail(), true, errors);
            checkCredential("password", rule.password(), request.getPassword(), user.getPassword() == null && !awaitingLink,
                    rule, errors);
            checkCredential("pin", rule.pin(), request.getPin(), (user.getPinHash() == null && !awaitingLink) || becomingAdmin,
                    rule, errors);
        }
        throwIfAny(errors);

        var active = request.getActive() == null ? user.isActive() : request.getActive();
        if (user.getRole() == Role.ADMIN && user.isActive() && (rule.role() != Role.ADMIN || !active)) {
            requireAnotherActiveAdmin(user.getId());
        }
        requireUniqueEmail(email, user.getId());
        if (rule.role() == Role.ADMIN && !blank(request.getPin())) {
            requireUniqueAdminPin(request.getPin(), user.getId());
        }

        var wasActive = user.isActive();
        var roleChanged = user.getRole() != rule.role();
        user.setName(name);
        user.setRole(rule.role());
        user.setActive(active);
        if (wasActive && !active) {
            // O3: a deactivated cashier's offline grants end (kept for O4's sync).
            offlineGrants.revokeForCashier(user.getId(), OfflineGrantStore.RevokeReason.CASHIER_DEACTIVATED,
                    UtcTimestamps.nowUtc(clock));
        }
        if ((wasActive && !active) || roleChanged) {
            // T0-6a: an open sign-in link no longer fits a deactivated person or a new role.
            var adminId = currentUserId();
            inviteStore.revokeOpen(user.getId(), InviteRevokeReason.REVOKED, adminId,
                    adminId == null ? null : userRepository.findById(adminId).map(User::getName).orElse(null),
                    UtcTimestamps.nowUtc(clock));
        }
        user.setEmail(rule.usesPassword() ? email : null);
        if (!rule.usesPassword()) {
            user.setPassword(null);
        } else if (!blank(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (!rule.usesPin()) {
            user.setPinHash(null);
        } else if (!blank(request.getPin())) {
            user.setPinHash(passwordEncoder.encode(request.getPin()));
        }
        userRepository.save(user);
        if (rule.usesPassword() && !blank(request.getPassword())) {
            clearPasswordLocks(user);
        }
        if (rule.usesPin() && !blank(request.getPin())) {
            clearPinLocks(user);
        }
        return toDto(user);
    }

    @Transactional
    public StaffDto setPassword(Long id, StaffRequests.SetPassword request) {
        var user = load(id);
        var rule = StaffRolePolicy.ruleFor(user.getRole()).orElseThrow(StaffExceptions.NotFound::new);
        var errors = new LinkedHashMap<String, String>();
        if (!rule.usesPassword()) {
            errors.put("password", rule.label() + " accounts don't use a password.");
        } else {
            checkCredential("password", Requirement.REQUIRED, request.getPassword(), true, rule, errors);
        }
        throwIfAny(errors);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        clearPasswordLocks(user);
        return toDto(user);
    }

    @Transactional
    public StaffDto setPin(Long id, StaffRequests.SetPin request) {
        var user = load(id);
        var rule = StaffRolePolicy.ruleFor(user.getRole()).orElseThrow(StaffExceptions.NotFound::new);
        var errors = new LinkedHashMap<String, String>();
        if (!rule.usesPin()) {
            errors.put("pin", rule.label() + " accounts don't use a PIN.");
        } else {
            checkCredential("pin", Requirement.REQUIRED, request.getPin(), true, rule, errors);
        }
        throwIfAny(errors);
        if (user.getRole() == Role.ADMIN) {
            requireUniqueAdminPin(request.getPin(), user.getId());
        }
        user.setPinHash(passwordEncoder.encode(request.getPin()));
        userRepository.save(user);
        clearPinLocks(user);
        return toDto(user);
    }

    /**
     * T0-6a: sets the credentials an accepted invite covers, with the same rules as the staff
     * screens (length, 6-digit PIN, unique admin PIN), and ends the matching locks. A requirement of
     * NONE means the link doesn't set that credential. Runs in the caller's transaction; the
     * caller is not signed in, so the unlock has no admin.
     */
    public void applyInviteCredentials(User user, Requirement password, Requirement pin, String newPassword, String newPin) {
        var rule = StaffRolePolicy.ruleFor(user.getRole()).orElseThrow(StaffExceptions.NotFound::new);
        var errors = new LinkedHashMap<String, String>();
        checkInviteCredential("password", password, newPassword, rule, errors);
        checkInviteCredential("pin", pin, newPin, rule, errors);
        throwIfAny(errors);
        if (!blank(newPin) && user.getRole() == Role.ADMIN) {
            requireUniqueAdminPin(newPin, user.getId());
        }
        var scopes = EnumSet.noneOf(LoginMethod.class);
        if (!blank(newPassword)) {
            user.setPassword(passwordEncoder.encode(newPassword));
            scopes.add(LoginMethod.PASSWORD);
            scopes.add(LoginMethod.CHANGE_PASSWORD);
        }
        if (!blank(newPin)) {
            user.setPinHash(passwordEncoder.encode(newPin));
            scopes.add(LoginMethod.PIN);
        }
        userRepository.save(user);
        if (!scopes.isEmpty()) {
            loginAttempts.unlock(user.getId(), scopes, null, "INVITE_ACCEPTED");
        }
    }

    private static void checkInviteCredential(String field, Requirement requirement, String value, RoleRule rule,
                                              Map<String, String> errors) {
        if (requirement == Requirement.NONE) {
            if (!blank(value)) {
                errors.put(field, "This link doesn't set a " + (field.equals("pin") ? "PIN" : "password") + ".");
            }
            return;
        }
        checkCredential(field, requirement, value, true, rule, errors);
    }

    /** A new password ends password sign-in locks and change-password blocks (same transaction). */
    private void clearPasswordLocks(User user) {
        if (isCurrentUser(user)) {
            // The new password is saved, but your own lock stays: another admin must unlock it.
            loginAttempts.selfUnlockRefused(user.getId(), "PASSWORD_RESET");
            return;
        }
        loginAttempts.unlock(user.getId(), EnumSet.of(LoginMethod.PASSWORD, LoginMethod.CHANGE_PASSWORD),
                currentUserId(), "PASSWORD_RESET");
    }

    /** A new PIN ends PIN sign-in locks and PIN blocks (same transaction). */
    private void clearPinLocks(User user) {
        if (isCurrentUser(user)) {
            loginAttempts.selfUnlockRefused(user.getId(), "PIN_RESET");
            return;
        }
        loginAttempts.unlock(user.getId(), EnumSet.of(LoginMethod.PIN), currentUserId(), "PIN_RESET");
    }

    /** WS1-1: admins can't clear their own sign-in locks (another admin must). */
    private static boolean isCurrentUser(User user) {
        var current = currentUserId();
        return current != null && current.equals(user.getId());
    }

    private static Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }

    private User load(Long id) {
        var user = userRepository.findById(id).orElseThrow(StaffExceptions.NotFound::new);
        return user;
    }

    private static String requireName(String name, Map<String, String> errors) {
        if (blank(name)) {
            errors.put("name", "Name is required.");
            return null;
        }
        if (name.trim().length() > 255) {
            errors.put("name", "Name must be 255 characters or fewer.");
        }
        return name.trim();
    }

    private static RoleRule ruleFor(Role role, Map<String, String> errors) {
        var rule = role == null ? null : StaffRolePolicy.ruleFor(role).orElse(null);
        if (rule == null) {
            errors.put("role", "Role must be one of " + StaffRolePolicy.RULES.stream().map(r -> r.role().name()).toList() + ".");
        }
        return rule;
    }

    /** Email is required for roles with a password and not allowed otherwise. Returned trimmed and lowercase. */
    private static String checkEmail(RoleRule rule, String raw, boolean required, Map<String, String> errors) {
        if (!rule.usesPassword()) {
            if (!blank(raw)) {
                errors.put("email", rule.label() + " accounts log in by PIN and don't have an email.");
            }
            return null;
        }
        if (blank(raw)) {
            if (required) {
                errors.put("email", "Email is required for " + rule.label() + ".");
            }
            return null;
        }
        var email = raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches() || email.length() > 255) {
            errors.put("email", "Email must be a valid email address.");
        }
        return email;
    }

    /**
     * Checks a password or PIN against the role's requirement. {@code missingIsError} is whether
     * leaving it out is a problem for a REQUIRED credential (on update, only when the person
     * doesn't already have one).
     */
    private static void checkCredential(String field, Requirement requirement, String value, boolean missingIsError,
                                        RoleRule rule, Map<String, String> errors) {
        var label = field.equals("pin") ? "PIN" : "Password";
        if (requirement == Requirement.NONE) {
            if (!blank(value)) {
                errors.put(field, rule.label() + " accounts don't use a " + label.toLowerCase(Locale.ROOT) + ".");
            }
            return;
        }
        if (blank(value)) {
            if (requirement == Requirement.REQUIRED && missingIsError) {
                errors.put(field, label + " is required for " + rule.label() + ".");
            }
            return;
        }
        if (field.equals("pin") && !PIN.matcher(value).matches()) {
            errors.put(field, "PIN must be exactly 6 digits.");
        }
        if (field.equals("password") && (value.length() < 6 || value.length() > 25)) {
            errors.put(field, "Password must be between 6 and 25 characters.");
        }
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new StaffExceptions.Invalid(errors);
        }
    }

    private void requireUniqueEmail(String email, Long selfId) {
        if (email == null) {
            return;
        }
        userRepository.findByEmail(email)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new StaffExceptions.Conflict("EMAIL_IN_USE", "That email is already in use.");
                });
    }

    /** Manager approval matches the entered PIN against every admin, so no two admins (active or
     * not) may share a PIN. */
    private void requireUniqueAdminPin(String pin, Long selfId) {
        var clash = userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.ADMIN && !user.getId().equals(selfId) && user.getPinHash() != null)
                .anyMatch(user -> passwordEncoder.matches(pin, user.getPinHash()));
        if (clash) {
            throw new StaffExceptions.Conflict("PIN_IN_USE", "Another admin already uses that PIN. Choose a different PIN.");
        }
    }

    private void requireAnotherActiveAdmin(Long selfId) {
        var others = userRepository.findByActiveTrueAndRoleIn(List.of(Role.ADMIN)).stream()
                .anyMatch(user -> !user.getId().equals(selfId));
        if (!others) {
            throw new StaffExceptions.Conflict("LAST_ADMIN", "This is the only active admin; add another admin first.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private StaffDto toDto(User user) {
        return toDto(user, loginAttempts.activeAccountLocks(), loginAttempts.activeBlocks(),
                inviteStore.latestForUsers(List.of(user.getId())).get(user.getId()), UtcTimestamps.nowUtc(clock));
    }

    private static StaffDto toDto(User user, Map<Long, Map<LoginMethod, LoginProtectionStore.ActiveLock>> locks,
                                  Map<Long, Map<LoginMethod, LocalDateTime>> blocks, UserInvite latestInvite,
                                  LocalDateTime now) {
        var userLocks = locks.getOrDefault(user.getId(), Map.of());
        var lockDtos = new LinkedHashMap<String, AccountLockDto>();
        lockDtos.put(LoginMethod.PASSWORD.name(), AccountLockDto.of(userLocks.get(LoginMethod.PASSWORD)));
        lockDtos.put(LoginMethod.PIN.name(), AccountLockDto.of(userLocks.get(LoginMethod.PIN)));
        var userBlocks = blocks.getOrDefault(user.getId(), Map.of());
        var blockDtos = new LinkedHashMap<String, AccountBlockDto>();
        blockDtos.put(LoginMethod.PIN.name(), AccountBlockDto.of(userBlocks.get(LoginMethod.PIN)));
        blockDtos.put(LoginMethod.MANAGER_PIN.name(), AccountBlockDto.of(userBlocks.get(LoginMethod.MANAGER_PIN)));
        blockDtos.put(LoginMethod.CHANGE_PASSWORD.name(), AccountBlockDto.of(userBlocks.get(LoginMethod.CHANGE_PASSWORD)));
        return new StaffDto(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(),
                user.getPassword() != null, user.getPinHash() != null, lockDtos, blockDtos,
                InviteSummary.of(latestInvite, now), null);
    }
}
