-- Stage 2.5: an adjustment's cash-out belongs to whichever shift was open when it was
-- authorized — which, per Stage 2.6, may be a different, later shift than the order's own
-- shift_id. Needed for correct expected-cash attribution at shift close. Split across GO
-- batches: the backfill UPDATE references the column added just above it, and SQL Server
-- compiles a script as one batch, so referencing a same-batch new column fails (same issue
-- V35 hit in Stage 2.6).
ALTER TABLE [order_adjustments] ADD [shift_id] BIGINT NULL;
GO

-- Best-effort backfill for any pre-existing rows: attribute to the order's own shift.
UPDATE [order_adjustments]
SET [shift_id] = (SELECT o.[shift_id] FROM [orders] o WHERE o.[id] = [order_adjustments].[order_id])
WHERE [shift_id] IS NULL;
GO

ALTER TABLE [order_adjustments] ALTER COLUMN [shift_id] BIGINT NOT NULL;
GO

ALTER TABLE [order_adjustments] ADD CONSTRAINT [FK_order_adjustments_shift]
    FOREIGN KEY ([shift_id]) REFERENCES [shifts]([id]);
