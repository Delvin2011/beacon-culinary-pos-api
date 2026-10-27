-- Account capture: a system-generated PM account number plus department and contact number.
-- account_number is derived from the identity (PM0001, PM0002, ...; widens past PM9999), so it
-- is unique, never reused, and existing accounts get one without a backfill.
ALTER TABLE [accounts] ADD
    [department]     NVARCHAR(100) NULL,
    [contact_number] NVARCHAR(20)  NULL,
    [account_number] AS CAST('PM' + RIGHT('0000' + CAST([id] AS VARCHAR(19)),
        CASE WHEN [id] > 9999 THEN LEN(CAST([id] AS VARCHAR(19))) ELSE 4 END) AS NVARCHAR(24)) PERSISTED;
GO

CREATE UNIQUE INDEX [UQ_accounts_account_number] ON [accounts]([account_number]);
