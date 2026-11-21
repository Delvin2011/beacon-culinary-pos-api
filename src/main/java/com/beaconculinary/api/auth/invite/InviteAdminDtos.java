package com.beaconculinary.api.auth.invite;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;

/** T0-6a: invite bodies on the staff endpoints (ADMIN). */
public final class InviteAdminDtos {
    private InviteAdminDtos() {
    }

    /**
     * POST /admin/staff/{id}/invite. purpose: STAFF_SETUP or RESET. credentials: PASSWORD, PIN or
     * BOTH; left out, every credential the role uses.
     */
    public record IssueRequest(InvitePurpose purpose, InviteCredentials credentials) {
    }

    /**
     * Returned once, when the invite is issued: the token is never shown again. link is null when
     * app.public-base-url isn't set (the frontend builds {its address}/invite#t={token}).
     * toString never shows the token.
     */
    public record IssuedInvite(long inviteId, InvitePurpose purpose, InviteCredentials credentials, String token,
                               String link, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedInvite[inviteId=" + inviteId + ", purpose=" + purpose + ", credentials=" + credentials
                    + ", token=<hidden>, expiresAt=" + expiresAt + "]";
        }
    }

    /** The person's latest invite, for the staff list (status NONE: never had one). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InviteSummary(InviteStatus status, InvitePurpose purpose, InviteCredentials credentials,
                                Instant createdAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        public static final InviteSummary NONE = new InviteSummary(InviteStatus.NONE, null, null, null, null, null, null);

        public static InviteSummary of(UserInvite invite, LocalDateTime now) {
            if (invite == null) {
                return NONE;
            }
            return new InviteSummary(invite.status(now), invite.purpose(), invite.credentials(),
                    instant(invite.createdAt()), instant(invite.expiresAt()), instant(invite.usedAt()),
                    instant(invite.revokedAt()));
        }

        private static Instant instant(LocalDateTime utc) {
            return utc == null ? null : com.beaconculinary.api.common.UtcTimestamps.toInstant(utc);
        }
    }
}
