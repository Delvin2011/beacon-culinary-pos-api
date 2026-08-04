-- Stage 1.1 (V3) never added a CHECK constraint on users.role, only a DEFAULT — add one now,
-- covering every role that already exists plus the new KITCHEN role (Stage 2.1).
ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [CK_users_role]
    CHECK ([role] IN ('USER', 'CASHIER', 'ADMIN', 'KITCHEN'));
