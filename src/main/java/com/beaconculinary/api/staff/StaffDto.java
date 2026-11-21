package com.beaconculinary.api.staff;

import com.beaconculinary.api.auth.invite.InviteAdminDtos.InviteSummary;
import com.beaconculinary.api.auth.invite.InviteAdminDtos.IssuedInvite;
import com.beaconculinary.api.users.Role;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** A staff member as the staff page shows it. Credentials are never returned, only whether each
 * is set. {@code locks} has the PASSWORD and PIN account locks, and {@code blocks} the short-term
 * PIN, MANAGER_PIN and CHANGE_PASSWORD blocks (WS1-1). T0-6a: {@code invite} is the latest sign-in
 * link's state; {@code issuedInvite} (with the one-time token) only on a create with setup INVITE. */
public record StaffDto(Long id, String name, String email, Role role, boolean active, boolean hasPassword, boolean hasPin,
                       Map<String, AccountLockDto> locks, Map<String, AccountBlockDto> blocks, InviteSummary invite,
                       @JsonInclude(JsonInclude.Include.NON_NULL) IssuedInvite issuedInvite) {

    public StaffDto withIssuedInvite(IssuedInvite issued) {
        return new StaffDto(id, name, email, role, active, hasPassword, hasPin, locks, blocks, invite, issued);
    }
}
