-- Stage 2.6: short-lived, single-use manager-PIN authorization tokens, decoupled from the
-- cashier's own session so an admin can approve an action at the cashier's terminal without
-- logging the cashier out.
CREATE TABLE [authorization_tokens] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [token]       NVARCHAR(64) NOT NULL UNIQUE,
    [admin_id]    BIGINT       NOT NULL,
    [created_at]  DATETIME2    NOT NULL DEFAULT SYSUTCDATETIME(),
    [expires_at]  DATETIME2    NOT NULL,
    [used]        BIT          NOT NULL DEFAULT 0,
    CONSTRAINT [FK_authorization_tokens_admin] FOREIGN KEY ([admin_id]) REFERENCES [users]([id])
);
