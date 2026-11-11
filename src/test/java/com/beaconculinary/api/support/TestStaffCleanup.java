package com.beaconculinary.api.support;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;

/**
 * Removes staff that a test created, so they don't pile up in the shared test database (for
 * example as extra active admins, which makes StaffManagementIntegrationTests' last-admin check
 * skip itself).
 *
 * <p>Deletes the user's manager-PIN tokens and sessions, then the user. user_auth_state cascades and
 * login_attempts has no foreign key. If anything else still refers to the user, it is deactivated
 * instead. Only ever runs against a test database (see TestDatabaseEnvironmentPostProcessor).
 */
public final class TestStaffCleanup {
    private TestStaffCleanup() {
    }

    public static void delete(JdbcTemplate jdbc, Collection<Long> userIds) {
        for (var id : userIds) {
            jdbc.update("DELETE FROM [authorization_tokens] WHERE [admin_id] = ?", id);
            jdbc.update("DELETE FROM [authorization_sessions] WHERE [admin_id] = ?", id);
            try {
                jdbc.update("DELETE FROM [users] WHERE [id] = ?", id);
            } catch (DataIntegrityViolationException stillReferenced) {
                jdbc.update("UPDATE [users] SET [active] = 0 WHERE [id] = ?", id);
            }
        }
    }

    /** Removes every user whose name starts with {@code prefix}; returns how many were found. */
    public static int deleteByNamePrefix(JdbcTemplate jdbc, String prefix) {
        List<Long> ids = jdbc.queryForList("SELECT [id] FROM [users] WHERE [name] LIKE ?", Long.class,
                prefix.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]") + "%");
        delete(jdbc, ids);
        return ids.size();
    }
}
