-- T1-4: each customer database has two logins (docs/2026-11-26-customer-migrations.md). The app login (login_secret_name,
-- password_secret_name: read, write and execute only) is what the running app uses; the migration login (these
-- columns: owner of the database) is used only by MigrateCustomersCommand. Only the secrets' NAMES are kept, with
-- Key Vault's rule as for the app login. NULL until registered; the command refuses a customer without them.
ALTER TABLE [tenant_databases] ADD
    [migration_login_secret_name]    VARCHAR(127) NULL,
    [migration_password_secret_name] VARCHAR(127) NULL;
GO

ALTER TABLE [tenant_databases] ADD
    CONSTRAINT [CK_tenant_databases_migration_login_secret_name] CHECK ([migration_login_secret_name] IS NULL OR (
        LEN([migration_login_secret_name]) >= 1
        AND [migration_login_secret_name] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND [migration_login_secret_name] NOT LIKE '-%' AND [migration_login_secret_name] NOT LIKE '%-'
        AND [migration_login_secret_name] NOT LIKE '%--%')),
    CONSTRAINT [CK_tenant_databases_migration_password_secret_name] CHECK ([migration_password_secret_name] IS NULL OR (
        LEN([migration_password_secret_name]) >= 1
        AND [migration_password_secret_name] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND [migration_password_secret_name] NOT LIKE '-%' AND [migration_password_secret_name] NOT LIKE '%-'
        AND [migration_password_secret_name] NOT LIKE '%--%')),
    -- Both or neither.
    CONSTRAINT [CK_tenant_databases_migration_secrets_together] CHECK (
        ([migration_login_secret_name] IS NULL AND [migration_password_secret_name] IS NULL)
        OR ([migration_login_secret_name] IS NOT NULL AND [migration_password_secret_name] IS NOT NULL));
GO
