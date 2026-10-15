package com.beaconculinary.api.staff;

import com.beaconculinary.api.users.Role;

/** A staff member as the staff page shows it. Credentials are never returned, only whether each
 * is set. */
public record StaffDto(Long id, String name, String email, Role role, boolean active, boolean hasPassword, boolean hasPin) {
}
