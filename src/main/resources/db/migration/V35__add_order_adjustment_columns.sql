-- Stage 2.6: immutable audit snapshot of the total at order creation (orders.total becomes
-- mutable, reduced by adjustments), plus a guard flag preventing EXTRAS_ONLY from being
-- applied twice to the same order.
ALTER TABLE [orders] ADD [original_total] DECIMAL(10, 2) NULL;
GO

UPDATE [orders] SET [original_total] = [total] WHERE [original_total] IS NULL;
GO

ALTER TABLE [orders] ALTER COLUMN [original_total] DECIMAL(10, 2) NOT NULL;
GO

ALTER TABLE [orders] ADD [extras_adjusted] BIT NOT NULL DEFAULT 0;
