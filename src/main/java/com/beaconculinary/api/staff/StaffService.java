package com.beaconculinary.api.staff;

import com.beaconculinary.api.staff.StaffRolePolicy.Requirement;
import com.beaconculinary.api.staff.StaffRolePolicy.RoleRule;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.User;
import com.beaconculinary.api.users.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
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

    public List<RoleRule> roles() {
        return StaffRolePolicy.RULES;
    }

    @Transactional(readOnly = true)
    public List<StaffDto> list() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != Role.USER)
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(StaffService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffDto get(Long id) {
        return toDto(load(id));
    }

    @Transactional
    public StaffDto create(StaffRequests.Create request) {
        var errors = new LinkedHashMap<String, String>();
        var name = requireName(request.getName(), errors);
        var rule = ruleFor(request.getRole(), errors);
        String email = null;
        if (rule != null) {
            email = checkEmail(rule, request.getEmail(), true, errors);
            checkCredential("password", rule.password(), request.getPassword(), true, rule, errors);
            checkCredential("pin", rule.pin(), request.getPin(), true, rule, errors);
        }
        throwIfAny(errors);

        requireUniqueEmail(email, null);
        if (rule.role() == Role.ADMIN) {
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

        String email = null;
        if (rule != null) {
            var emailInput = request.getEmail() != null ? request.getEmail() : user.getEmail();
            email = checkEmail(rule, rule.usesPassword() ? emailInput : request.getEmail(), true, errors);
            checkCredential("password", rule.password(), request.getPassword(), user.getPassword() == null, rule, errors);
            checkCredential("pin", rule.pin(), request.getPin(), user.getPinHash() == null || becomingAdmin, rule, errors);
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

        user.setName(name);
        user.setRole(rule.role());
        user.setActive(active);
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
        return toDto(user);
    }

    private User load(Long id) {
        var user = userRepository.findById(id).orElseThrow(StaffExceptions.NotFound::new);
        if (user.getRole() == Role.USER) {
            throw new StaffExceptions.NotFound();
        }
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

    private static StaffDto toDto(User user) {
        return new StaffDto(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(),
                user.getPassword() != null, user.getPinHash() != null);
    }
}
