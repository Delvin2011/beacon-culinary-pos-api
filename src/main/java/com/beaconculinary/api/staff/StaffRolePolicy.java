package com.beaconculinary.api.staff;

import com.beaconculinary.api.users.Role;

import java.util.List;
import java.util.Optional;

/**
 * Staff management: which credentials each role uses. The frontend's staff form is built from
 * this list (GET /admin/staff/roles) and the service enforces the same rules, so they can never
 * drift apart.
 *
 * <ul>
 *   <li>ADMIN — email + password (back office) and a PIN (manager approvals, POS tile).</li>
 *   <li>CASHIER, KITCHEN, BARISTA — PIN only (POS / kitchen / bar display tiles).</li>
 *   <li>HEAD_CHEF — email + password; PIN optional (kitchen display).</li>
 *   <li>STOCK_ADMIN, STOCK_CLERK — email + password only.</li>
 * </ul>
 * USER (left over from the original e-commerce scaffold) can't be created here.
 */
public final class StaffRolePolicy {
    public enum Requirement {
        REQUIRED,
        OPTIONAL,
        NONE
    }

    /** One role's credential rules. An email is needed exactly when the role uses a password. */
    public record RoleRule(Role role, String label, Requirement password, Requirement pin) {
        public boolean usesPassword() {
            return password != Requirement.NONE;
        }

        public boolean usesPin() {
            return pin != Requirement.NONE;
        }
    }

    public static final List<RoleRule> RULES = List.of(
            new RoleRule(Role.ADMIN, "Admin (Manager)", Requirement.REQUIRED, Requirement.REQUIRED),
            new RoleRule(Role.CASHIER, "Cashier", Requirement.NONE, Requirement.REQUIRED),
            new RoleRule(Role.KITCHEN, "Kitchen", Requirement.NONE, Requirement.REQUIRED),
            new RoleRule(Role.BARISTA, "Barista", Requirement.NONE, Requirement.REQUIRED),
            new RoleRule(Role.HEAD_CHEF, "Head Chef", Requirement.REQUIRED, Requirement.OPTIONAL),
            new RoleRule(Role.STOCK_ADMIN, "Stock Admin", Requirement.REQUIRED, Requirement.NONE),
            new RoleRule(Role.STOCK_CLERK, "Stock Clerk", Requirement.REQUIRED, Requirement.NONE));

    private StaffRolePolicy() {
    }

    public static Optional<RoleRule> ruleFor(Role role) {
        return RULES.stream().filter(rule -> rule.role() == role).findFirst();
    }
}
