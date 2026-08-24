-- Stage 5.2.5: a physical stock count submitted for one location, reviewed (approved or
-- rejected) by a STOCK_ADMIN/ADMIN as a binary credibility check — never an edit of what the
-- clerk actually counted.
CREATE TABLE [stock_takes] (
    [id]            BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [location_id]   BIGINT        NOT NULL,
    [submitted_by]  BIGINT        NOT NULL,
    [submitted_at]  DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
    [status]        NVARCHAR(10)  NOT NULL DEFAULT 'SUBMITTED',
    [reviewed_by]   BIGINT        NULL,
    [reviewed_at]   DATETIME2     NULL,
    [note]          NVARCHAR(255) NULL,
    CONSTRAINT [FK_stock_takes_location] FOREIGN KEY ([location_id]) REFERENCES [locations]([id]),
    CONSTRAINT [FK_stock_takes_submitted_by] FOREIGN KEY ([submitted_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_stock_takes_reviewed_by] FOREIGN KEY ([reviewed_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_stock_takes_status] CHECK ([status] IN ('SUBMITTED', 'APPROVED', 'REJECTED'))
);
