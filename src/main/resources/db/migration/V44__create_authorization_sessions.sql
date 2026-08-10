-- Stage 3.3: session-scoped manager-PIN authorization for the cashier-terminal management menu
-- (Void/Refund/Discount/Cashup Summary) — longer-lived and reusable across multiple actions,
-- unlike Stage 2.6's single-use authorization_tokens.
CREATE TABLE [authorization_sessions] (
    [id]             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [session_token]  NVARCHAR(64) NOT NULL UNIQUE,
    [admin_id]       BIGINT       NOT NULL,
    [created_at]     DATETIME2    NOT NULL DEFAULT SYSUTCDATETIME(),
    [expires_at]     DATETIME2    NOT NULL,
    CONSTRAINT [FK_authorization_sessions_admin] FOREIGN KEY ([admin_id]) REFERENCES [users]([id])
);
