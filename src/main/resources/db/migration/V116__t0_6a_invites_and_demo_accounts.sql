-- T0-6a: invite links and demo accounts (docs/2026-11-21-t0-6-invites-api-contract.md).
--
-- 1. users.is_demo: the eight accounts seeded by V13, V14, V32, V69, V89 and V98 (all
--    @canteen.local). With the demo guard on (always in the prod profile) they can't sign in by
--    any method. A database without them is fine: nothing is marked.
-- 2. user_invites: single-use links to set a password and/or PIN. Only the SHA-256 (hex) of the
--    256-bit token is stored; the token itself is shown once and never stored or logged.
--    At most one open (unused, unrevoked) invite per user; issuing a new one revokes the old one
--    as REPLACED. created_by is null for invites issued by the operator's first-admin command.
-- 3. login_attempts.method gains INVITE (lookup and accept); outcome gains DEMO_ACCOUNT (a demo
--    account refused by the guard; never counted as a failure).
-- 4. user_sessions.end_reason gains CREDENTIALS_CHANGED (every session ends when an invite is
--    accepted).
--
-- created_by / revoked_by have no foreign key, like the other audit columns; user_id cascades
-- with its user, like user_auth_state.

-- 1. Demo accounts --------------------------------------------------------------------------------

ALTER TABLE [users] ADD [is_demo] BIT NOT NULL CONSTRAINT [DF_users_is_demo] DEFAULT 0;
GO

UPDATE [users] SET [is_demo] = 1
WHERE LOWER([email]) IN ('cashier@canteen.local', 'admin@canteen.local', 'cashierb@canteen.local',
                         'kitchen@canteen.local', 'stockclerk@canteen.local', 'stockadmin@canteen.local',
                         'headchef@canteen.local', 'barista@canteen.local');
PRINT CONCAT('T0-6a: demo accounts marked: ', @@ROWCOUNT);
GO

-- 2. Invites --------------------------------------------------------------------------------------

CREATE TABLE [user_invites] (
    [id]               BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_user_invites] PRIMARY KEY,
    [user_id]          BIGINT        NOT NULL CONSTRAINT [FK_user_invites_users]
                           REFERENCES [users]([id]) ON DELETE CASCADE,
    [purpose]          VARCHAR(20)   NOT NULL CONSTRAINT [CK_user_invites_purpose]
                           CHECK ([purpose] IN ('FIRST_ADMIN', 'STAFF_SETUP', 'RESET')),
    [credentials]      VARCHAR(10)   NOT NULL CONSTRAINT [CK_user_invites_credentials]
                           CHECK ([credentials] IN ('PASSWORD', 'PIN', 'BOTH')),
    [token_hash]       CHAR(64)      NOT NULL,
    [created_via]      VARCHAR(10)   NOT NULL CONSTRAINT [CK_user_invites_created_via]
                           CHECK ([created_via] IN ('ADMIN', 'OPERATOR')),
    [created_by]       BIGINT        NULL,
    [created_by_name]  NVARCHAR(255) NULL,
    [operator_forced]  BIT           NOT NULL CONSTRAINT [DF_user_invites_operator_forced] DEFAULT 0,
    [operator_reason]  NVARCHAR(500) NULL,
    [created_at]       DATETIME2     NOT NULL,
    [expires_at]       DATETIME2     NOT NULL,
    [used_at]          DATETIME2     NULL,
    [revoked_at]       DATETIME2     NULL,
    [revoked_by]       BIGINT        NULL,
    [revoked_by_name]  NVARCHAR(255) NULL,
    [revoke_reason]    VARCHAR(10)   NULL CONSTRAINT [CK_user_invites_revoke_reason]
                           CHECK ([revoke_reason] IN ('REPLACED', 'REVOKED')),
    CONSTRAINT [CK_user_invites_expiry] CHECK ([expires_at] > [created_at]),
    CONSTRAINT [CK_user_invites_closed_once] CHECK ([used_at] IS NULL OR [revoked_at] IS NULL),
    CONSTRAINT [CK_user_invites_revoked]
        CHECK (([revoked_at] IS NULL AND [revoke_reason] IS NULL)
            OR ([revoked_at] IS NOT NULL AND [revoke_reason] IS NOT NULL)),
    CONSTRAINT [CK_user_invites_creator]
        CHECK (([created_via] = 'ADMIN' AND [created_by] IS NOT NULL AND [operator_forced] = 0)
            OR ([created_via] = 'OPERATOR' AND [created_by] IS NULL)),
    CONSTRAINT [CK_user_invites_forced_reason] CHECK ([operator_forced] = 0 OR [operator_reason] IS NOT NULL)
);
GO

CREATE UNIQUE INDEX [UX_user_invites_token_hash] ON [user_invites]([token_hash]);
CREATE UNIQUE INDEX [UX_user_invites_one_open] ON [user_invites]([user_id])
    WHERE [used_at] IS NULL AND [revoked_at] IS NULL;
CREATE INDEX [IX_user_invites_user_created] ON [user_invites]([user_id], [created_at]);
GO

-- 3. Login attempts -------------------------------------------------------------------------------

ALTER TABLE [login_attempts] DROP CONSTRAINT [CK_login_attempts_method];
ALTER TABLE [login_attempts] ADD CONSTRAINT [CK_login_attempts_method]
    CHECK ([method] IN ('PASSWORD', 'PIN', 'MANAGER_PIN', 'CHANGE_PASSWORD', 'INVITE'));

ALTER TABLE [login_attempts] DROP CONSTRAINT [CK_login_attempts_outcome];
ALTER TABLE [login_attempts] ADD CONSTRAINT [CK_login_attempts_outcome]
    CHECK ([outcome] IN ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED',
                         'LOCKED', 'RATE_LIMITED', 'VALIDATION', 'DEVICE_NOT_REGISTERED',
                         'TILL_LOCKED', 'SHIFT_OPEN_ON_OTHER_TILL', 'DEMO_ACCOUNT'));
GO

-- 4. Sessions -------------------------------------------------------------------------------------

ALTER TABLE [user_sessions] DROP CONSTRAINT [CK_user_sessions_end_reason];
ALTER TABLE [user_sessions] ADD CONSTRAINT [CK_user_sessions_end_reason]
    CHECK ([end_reason] IN ('LOGOUT', 'LOCK', 'REPLACED', 'IDLE', 'ADMIN', 'SHIFT_FORCE_CLOSED', 'EXPIRED',
                            'CREDENTIALS_CHANGED'));
GO
