-- POS Oversight A5: who closed each shift, plus the two expected-cash components snapshotted
-- at close, so the activity view can show the parts and not only the stored total.
ALTER TABLE [shifts] ADD [closed_by] BIGINT NULL
    CONSTRAINT [FK_shifts_closed_by] FOREIGN KEY REFERENCES [users]([id]);
ALTER TABLE [shifts] ADD [cash_sales_at_close] DECIMAL(10, 2) NULL;
ALTER TABLE [shifts] ADD [cash_refunds_at_close] DECIMAL(10, 2) NULL;
GO

-- Best-effort backfill: historic closes are attributed to the shift owner. Past admin
-- force-closes cannot be distinguished; this is an accepted historical gap.
-- cash_sales_at_close / cash_refunds_at_close stay NULL for historic shifts (=> RECALCULATED).
UPDATE [shifts] SET [closed_by] = [cashier_id] WHERE [status] = 'CLOSED' AND [closed_by] IS NULL;
