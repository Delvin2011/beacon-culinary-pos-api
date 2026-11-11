-- WS1-1 login protection (Phase 1, monitor mode).
--
-- login_attempts: one row per attempt on password login, PIN login, manager PIN and change
-- password. Rule counts are read from here, including for unknown emails, which have no user.
-- user_auth_state: per-user, per-method lock state and last success (a success resets the
-- failure count for that method).
--
-- All timestamps are UTC (DATETIME2), written by the application from its Clock via
-- UtcTimestamps.nowUtc; the SYSUTCDATETIME() default is only a fallback.
--
-- "Admin unlock required" is encoded as lock_level = 3 with locked_until = 9999-12-31 23:59:59.9999999,
-- so "is locked" is always the single test locked_until > now.
--
-- Foreign keys: login_attempts.user_id has none on purpose (the audit outlives a deleted user,
-- and DELETE /users/{id} must keep working). user_auth_state cascades on user delete.
-- unlocked_by has no FK because SQL Server rejects a second cascade path to users.

CREATE TABLE [login_attempts] (
    [id]               BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_login_attempts] PRIMARY KEY,
    [attempted_at]     DATETIME2     NOT NULL CONSTRAINT [DF_login_attempts_attempted_at] DEFAULT SYSUTCDATETIME(),
    [method]           VARCHAR(20)   NOT NULL CONSTRAINT [CK_login_attempts_method]
                           CHECK ([method] IN ('PASSWORD', 'PIN', 'MANAGER_PIN', 'CHANGE_PASSWORD')),
    [identifier]       NVARCHAR(255) NULL,
    [user_id]          BIGINT        NULL,
    [client_ip]        VARCHAR(45)   NULL,
    [remote_addr]      VARCHAR(45)   NULL,
    [forwarded_for]    NVARCHAR(512) NULL,
    [user_agent]       NVARCHAR(512) NULL,
    [device_id]        NVARCHAR(100) NULL,
    [outcome]          VARCHAR(30)   NOT NULL CONSTRAINT [CK_login_attempts_outcome]
                           CHECK ([outcome] IN ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED',
                                                'LOCKED', 'RATE_LIMITED', 'VALIDATION')),
    [would_block]      BIT           NOT NULL CONSTRAINT [DF_login_attempts_would_block] DEFAULT 0,
    [rule_triggered]   VARCHAR(50)   NULL,
    [pin_non_standard] BIT           NULL
);
GO

CREATE INDEX [IX_login_attempts_attempted_at] ON [login_attempts]([attempted_at]);
CREATE INDEX [IX_login_attempts_method_identifier] ON [login_attempts]([method], [identifier], [attempted_at]);
CREATE INDEX [IX_login_attempts_method_user] ON [login_attempts]([method], [user_id], [attempted_at]);
CREATE INDEX [IX_login_attempts_client_ip] ON [login_attempts]([client_ip], [attempted_at]);
GO

CREATE TABLE [user_auth_state] (
    [user_id]         BIGINT      NOT NULL CONSTRAINT [FK_user_auth_state_users]
                          REFERENCES [users]([id]) ON DELETE CASCADE,
    [scope]           VARCHAR(20) NOT NULL CONSTRAINT [CK_user_auth_state_scope]
                          CHECK ([scope] IN ('PASSWORD', 'PIN', 'MANAGER_PIN', 'CHANGE_PASSWORD')),
    [lock_level]      INT         NOT NULL CONSTRAINT [DF_user_auth_state_lock_level] DEFAULT 0
                          CONSTRAINT [CK_user_auth_state_lock_level] CHECK ([lock_level] BETWEEN 0 AND 3),
    [locked_until]    DATETIME2   NULL,
    [locked_at]       DATETIME2   NULL,
    [lock_reason]     VARCHAR(50) NULL,
    [unlocked_by]     BIGINT      NULL,
    [unlocked_at]     DATETIME2   NULL,
    [last_success_at] DATETIME2   NULL,
    CONSTRAINT [PK_user_auth_state] PRIMARY KEY ([user_id], [scope])
);
