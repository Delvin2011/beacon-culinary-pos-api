-- Stage 4 Part B: canteen accounts (e.g. departments/companies) that can pay for orders on
-- credit, settled later via account_payments (V46).
CREATE TABLE [accounts] (
    [id]             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]           NVARCHAR(100) NOT NULL,
    [contact_email]  NVARCHAR(255) NULL,
    [active]         BIT NOT NULL DEFAULT 1
);
