-- Staff management: which credentials a user has now depends on their role. PIN-only staff
-- (cashiers, kitchen) have no email or password, so both columns become nullable. Existing rows
-- keep their values.
ALTER TABLE [users] ALTER COLUMN [email] NVARCHAR(255) NULL;
ALTER TABLE [users] ALTER COLUMN [password] NVARCHAR(255) NULL;
GO

-- Email was only checked for uniqueness in application code. Enforce it in the database too,
-- ignoring NULLs (SQL Server would otherwise allow only one NULL in a unique index).
CREATE UNIQUE INDEX [UX_users_email] ON [users]([email]) WHERE [email] IS NOT NULL;
