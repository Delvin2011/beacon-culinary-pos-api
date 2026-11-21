package com.beaconculinary.api.auth.invite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * T0-6a: SQL for user_invites (V116). Runs in the caller's transaction. Times are UTC (application
 * clock). Closing statements only touch open rows, so closing twice changes nothing.
 */
@Repository
public class UserInviteStore {
    private final JdbcTemplate jdbc;

    public UserInviteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            [id], [user_id], [purpose], [credentials], [created_via], [created_by], [created_by_name],
            [operator_forced], [created_at], [expires_at], [used_at], [revoked_at], [revoked_by],
            [revoked_by_name], [revoke_reason]""";

    private static final RowMapper<UserInvite> ROW = (rs, i) -> {
        var reason = rs.getString("revoke_reason");
        return new UserInvite(rs.getLong("id"), rs.getLong("user_id"), InvitePurpose.valueOf(rs.getString("purpose")),
                InviteCredentials.valueOf(rs.getString("credentials")), rs.getString("created_via"),
                rs.getObject("created_by", Long.class), rs.getString("created_by_name"),
                rs.getBoolean("operator_forced"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class), rs.getObject("used_at", LocalDateTime.class),
                rs.getObject("revoked_at", LocalDateTime.class), rs.getObject("revoked_by", Long.class),
                rs.getString("revoked_by_name"), reason == null ? null : InviteRevokeReason.valueOf(reason));
    };

    /**
     * Serialises invite changes for one user (issue, revoke, accept) until the transaction ends, so
     * the one-open-invite rule never meets a race. False when the user doesn't exist.
     */
    public boolean lockUser(long userId) {
        return !jdbc.queryForList("SELECT [id] FROM [users] WITH (UPDLOCK, HOLDLOCK) WHERE [id] = ?", Long.class, userId)
                .isEmpty();
    }

    /** Inserts an invite issued by an admin; returns its id. */
    public long insertByAdmin(long userId, InvitePurpose purpose, InviteCredentials credentials, String tokenHash,
                              long createdBy, String createdByName, LocalDateTime createdAt, LocalDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO [user_invites] ([user_id], [purpose], [credentials], [token_hash], [created_via],
                                            [created_by], [created_by_name], [created_at], [expires_at])
                OUTPUT INSERTED.[id]
                VALUES (?, ?, ?, ?, 'ADMIN', ?, ?, ?, ?)
                """, Long.class, userId, purpose.name(), credentials.name(), tokenHash, createdBy, createdByName,
                createdAt, expiresAt);
    }

    public Optional<UserInvite> findByTokenHash(String tokenHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM [user_invites] WHERE [token_hash] = ?", ROW, tokenHash)
                .stream().findFirst();
    }

    public Optional<UserInvite> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM [user_invites] WHERE [id] = ?", ROW, id).stream().findFirst();
    }

    /** The user's open invite (unused, unrevoked; possibly expired). */
    public Optional<UserInvite> openForUser(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM [user_invites] WHERE [user_id] = ? AND [used_at] IS NULL AND [revoked_at] IS NULL",
                ROW, userId).stream().findFirst();
    }

    /** Each user's latest invite (by creation), for the staff list. Users without one are absent. */
    public Map<Long, UserInvite> latestForUsers(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Long, UserInvite>();
        // SQL Server allows 2100 parameters per statement.
        var ids = userIds.stream().distinct().toList();
        for (int from = 0; from < ids.size(); from += 1000) {
            var chunk = ids.subList(from, Math.min(from + 1000, ids.size()));
            var marks = chunk.stream().map(id -> "?").collect(Collectors.joining(", "));
            jdbc.query("""
                    SELECT %s FROM (
                        SELECT *, ROW_NUMBER() OVER (PARTITION BY [user_id] ORDER BY [created_at] DESC, [id] DESC) AS [rn]
                        FROM [user_invites] WHERE [user_id] IN (%s)
                    ) latest WHERE [rn] = 1
                    """.formatted(COLUMNS, marks), ROW, chunk.toArray())
                    .forEach(invite -> result.put(invite.userId(), invite));
        }
        return result;
    }

    /** Revokes the user's open invite, if any. Returns how many were revoked (0 or 1). */
    public int revokeOpen(long userId, InviteRevokeReason reason, Long revokedBy, String revokedByName, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [user_invites] SET [revoked_at] = ?, [revoked_by] = ?, [revoked_by_name] = ?, [revoke_reason] = ?
                WHERE [user_id] = ? AND [used_at] IS NULL AND [revoked_at] IS NULL
                """, now, revokedBy, revokedByName, reason.name(), userId);
    }

    /** Marks an open invite used; false when it was already used or revoked. */
    public boolean markUsed(long id, LocalDateTime now) {
        return jdbc.update("UPDATE [user_invites] SET [used_at] = ? WHERE [id] = ? AND [used_at] IS NULL AND [revoked_at] IS NULL",
                now, id) > 0;
    }
}
