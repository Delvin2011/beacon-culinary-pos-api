-- Widens orders.status from Phase 1's single CONFIRMED value to the full Phase 2 lifecycle
-- (Stage 2.1 KDS). The original CHECK/DEFAULT on [status] were created inline (unnamed) in
-- V23, so SQL Server auto-generated their names — look them up by column instead of guessing
-- the generated suffix.
DECLARE @checkName NVARCHAR(200), @defaultName NVARCHAR(200);

SELECT @checkName = cc.name
FROM sys.check_constraints cc
JOIN sys.columns c ON c.object_id = cc.parent_object_id AND c.column_id = cc.parent_column_id
WHERE cc.parent_object_id = OBJECT_ID('orders') AND c.name = 'status';

SELECT @defaultName = dc.name
FROM sys.default_constraints dc
JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
WHERE dc.parent_object_id = OBJECT_ID('orders') AND c.name = 'status';

EXEC('ALTER TABLE [orders] DROP CONSTRAINT [' + @checkName + ']');
EXEC('ALTER TABLE [orders] DROP CONSTRAINT [' + @defaultName + ']');

-- CONFIRMED and PENDING are the same state (paid, not yet touched by kitchen) — just renamed
-- now that there's a fuller lifecycle.
UPDATE [orders] SET [status] = 'PENDING' WHERE [status] = 'CONFIRMED';

ALTER TABLE [orders] ADD CONSTRAINT [DF_orders_status] DEFAULT 'PENDING' FOR [status];
ALTER TABLE [orders] WITH CHECK ADD CONSTRAINT [CK_orders_status]
    CHECK ([status] IN ('PENDING', 'IN_PROGRESS', 'DONE', 'COLLECTED', 'VOIDED', 'REFUNDED'));
