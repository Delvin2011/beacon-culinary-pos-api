-- T1-1: the platform database (docs/2026-11-24-platform-database.md). Its own database (locally pos_platform) with its
-- own Flyway history (location db/platform), never mixed with a customer database's migrations. It lists the
-- customers (tenants), the addresses they are reached at and where their databases are. It stores the NAMES of the
-- secrets that hold database logins and passwords, never their values. Plans and entitlements (T2) and platform
-- operators (T3) come later as their own tables referencing tenants(id).

-- A customer. slug: 3-40 lower-case letters, digits and hyphens, starting and ending with a letter or digit (it is
-- the first label of <slug>.pos.optimumscs.com). The binary collation makes the lower-case rule hold whatever the
-- database's (case-insensitive) collation is.
CREATE TABLE [tenants] (
    [id]           BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_tenants] PRIMARY KEY,
    [slug]         VARCHAR(40)    NOT NULL CONSTRAINT [UQ_tenants_slug] UNIQUE,
    [display_name] NVARCHAR(200)  NOT NULL,
    [status]       VARCHAR(20)    NOT NULL CONSTRAINT [CK_tenants_status]
                   CHECK ([status] IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'MIGRATION_FAILED', 'ARCHIVED')),
    [created_at]   DATETIME2(3)   NOT NULL CONSTRAINT [DF_tenants_created_at] DEFAULT SYSUTCDATETIME(),
    [updated_at]   DATETIME2(3)   NOT NULL CONSTRAINT [DF_tenants_updated_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [CK_tenants_slug] CHECK (LEN([slug]) BETWEEN 3 AND 40
        AND DATALENGTH([slug]) = LEN([slug])
        AND [slug] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND [slug] NOT LIKE '-%' AND [slug] NOT LIKE '%-'),
    CONSTRAINT [CK_tenants_display_name] CHECK (LEN([display_name]) >= 1)
);
GO

-- The addresses a customer is reached at: stored lower-case, without a port or a trailing dot (the directory
-- normalises a request's host the same way). A host belongs to one customer; each customer has at most one primary.
CREATE TABLE [tenant_hosts] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_tenant_hosts] PRIMARY KEY,
    [tenant_id]  BIGINT        NOT NULL CONSTRAINT [FK_tenant_hosts_tenant] REFERENCES [tenants] ([id]),
    [host]       VARCHAR(253)  NOT NULL CONSTRAINT [UQ_tenant_hosts_host] UNIQUE,
    [is_primary] BIT           NOT NULL CONSTRAINT [DF_tenant_hosts_is_primary] DEFAULT 0,
    [created_at] DATETIME2(3)  NOT NULL CONSTRAINT [DF_tenant_hosts_created_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [CK_tenant_hosts_host] CHECK (LEN([host]) >= 3
        AND DATALENGTH([host]) = LEN([host])
        AND [host] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9.-]%'
        AND [host] NOT LIKE '.%' AND [host] NOT LIKE '%.' AND [host] NOT LIKE '%..%')
);
GO

CREATE INDEX [IX_tenant_hosts_tenant] ON [tenant_hosts] ([tenant_id]);
GO

-- One primary host per customer.
CREATE UNIQUE INDEX [UX_tenant_hosts_one_primary] ON [tenant_hosts] ([tenant_id]) WHERE [is_primary] = 1;
GO

-- Where a customer's database is: one row per customer, no credentials. The login and the password are secrets (the
-- environment locally, Key Vault in Azure); only the secrets' NAMES are kept here (Key Vault's rule: lower-case
-- letters, digits and single hyphens). Two customers never share a database.
CREATE TABLE [tenant_databases] (
    [tenant_id]             BIGINT         NOT NULL CONSTRAINT [PK_tenant_databases] PRIMARY KEY
                            CONSTRAINT [FK_tenant_databases_tenant] REFERENCES [tenants] ([id]),
    [server_host]           VARCHAR(253)   NOT NULL,
    [server_port]           INT            NOT NULL CONSTRAINT [DF_tenant_databases_server_port] DEFAULT 1433,
    [database_name]         NVARCHAR(128)  NOT NULL,
    [connection_options]    VARCHAR(500)   NULL,
    [login_secret_name]     VARCHAR(127)   NOT NULL,
    [password_secret_name]  VARCHAR(127)   NOT NULL,
    [built_from_baseline]   BIT            NOT NULL,
    [expected_collation]    VARCHAR(128)   NOT NULL
                            CONSTRAINT [DF_tenant_databases_expected_collation] DEFAULT 'SQL_Latin1_General_CP1_CI_AS',
    [last_migrated_version] VARCHAR(50)    NULL,
    [last_migrated_at]      DATETIME2(3)   NULL,
    [updated_at]            DATETIME2(3)   NOT NULL CONSTRAINT [DF_tenant_databases_updated_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [UQ_tenant_databases_location] UNIQUE ([server_host], [server_port], [database_name]),
    CONSTRAINT [CK_tenant_databases_server_port] CHECK ([server_port] BETWEEN 1 AND 65535),
    CONSTRAINT [CK_tenant_databases_server_host] CHECK (LEN([server_host]) >= 1
        AND [server_host] COLLATE Latin1_General_BIN2 NOT LIKE '%[^A-Za-z0-9.-]%'),
    CONSTRAINT [CK_tenant_databases_database_name] CHECK (LEN([database_name]) >= 1),
    -- Credentials never go in the options (encrypt=true;trustServerCertificate=false and the like only).
    CONSTRAINT [CK_tenant_databases_connection_options] CHECK ([connection_options] IS NULL OR (
        [connection_options] NOT LIKE '%password%' AND [connection_options] NOT LIKE '%pwd%'
        AND [connection_options] NOT LIKE '%user%' AND [connection_options] NOT LIKE '%uid=%'
        AND [connection_options] NOT LIKE '%secret%')),
    CONSTRAINT [CK_tenant_databases_login_secret_name] CHECK (LEN([login_secret_name]) >= 1
        AND [login_secret_name] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND [login_secret_name] NOT LIKE '-%' AND [login_secret_name] NOT LIKE '%-' AND [login_secret_name] NOT LIKE '%--%'),
    CONSTRAINT [CK_tenant_databases_password_secret_name] CHECK (LEN([password_secret_name]) >= 1
        AND [password_secret_name] COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND [password_secret_name] NOT LIKE '-%' AND [password_secret_name] NOT LIKE '%-' AND [password_secret_name] NOT LIKE '%--%')
);
GO

-- What operators (and, from T1-4, provisioning) did to a customer and how it ended. tenant_slug is kept even when a
-- request was refused before any customer existed. message is never a secret.
CREATE TABLE [provisioning_runs] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_provisioning_runs] PRIMARY KEY,
    [tenant_id]   BIGINT          NULL CONSTRAINT [FK_provisioning_runs_tenant] REFERENCES [tenants] ([id]),
    [tenant_slug] VARCHAR(40)     NOT NULL,
    [action]      VARCHAR(30)     NOT NULL,
    [operator]    NVARCHAR(100)   NULL,
    [started_at]  DATETIME2(3)    NOT NULL,
    [finished_at] DATETIME2(3)    NULL,
    [outcome]     VARCHAR(20)     NOT NULL CONSTRAINT [CK_provisioning_runs_outcome]
                  CHECK ([outcome] IN ('RUNNING', 'SUCCEEDED', 'UNCHANGED', 'REFUSED', 'FAILED')),
    [message]     NVARCHAR(1000)  NULL,
    CONSTRAINT [CK_provisioning_runs_action] CHECK (LEN([action]) >= 1
        AND [action] COLLATE Latin1_General_BIN2 NOT LIKE '%[^A-Z_]%')
);
GO

CREATE INDEX [IX_provisioning_runs_tenant] ON [provisioning_runs] ([tenant_id], [started_at]);
GO
