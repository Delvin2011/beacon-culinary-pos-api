package com.beaconculinary.api.staff;

import com.beaconculinary.api.users.Role;

import java.util.Map;

/** A staff member as the staff page shows it. Credentials are never returned, only whether each
 * is set. {@code locks} has the PASSWORD and PIN sign-in lock state (WS1-1). */
public record StaffDto(Long id, String name, String email, Role role, boolean active, boolean hasPassword, boolean hasPin,
                       Map<String, AccountLockDto> locks) {
}
