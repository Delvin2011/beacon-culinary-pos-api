package com.beaconculinary.api.auth.invite;

import java.time.LocalDateTime;

/** T0-6a: one row of user_invites (V116). Times are UTC. The token hash is never read back out. */
public record UserInvite(long id, long userId, InvitePurpose purpose, InviteCredentials credentials,
                         String createdVia, Long createdBy, String createdByName, boolean operatorForced,
                         LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime usedAt,
                         LocalDateTime revokedAt, Long revokedBy, String revokedByName,
                         InviteRevokeReason revokeReason) {

    public InviteStatus status(LocalDateTime now) {
        if (usedAt != null) {
            return InviteStatus.USED;
        }
        if (revokedAt != null) {
            return InviteStatus.REVOKED;
        }
        return now.isBefore(expiresAt) ? InviteStatus.PENDING : InviteStatus.EXPIRED;
    }

    /** Unused and unrevoked, expired or not (the one-open-invite index counts expired ones too). */
    public boolean open() {
        return usedAt == null && revokedAt == null;
    }
}
