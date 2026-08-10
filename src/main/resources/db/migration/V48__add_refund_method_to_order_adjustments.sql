-- Stage 4 Part C: every void/refund/discount pays out as cash or an account-balance credit,
-- computed automatically from whether the order's payment included ACCOUNT — never
-- client-supplied. Split across GO batches: the backfill UPDATE references the column added
-- just above it, and the final NOT NULL tightening follows it — same pattern V35/V41 needed.
ALTER TABLE [order_adjustments] ADD [refund_method] NVARCHAR(20) NULL
    CONSTRAINT [CK_order_adjustments_refund_method] CHECK ([refund_method] IN ('CASH', 'ACCOUNT_BALANCE'));
ALTER TABLE [order_adjustments] ADD [account_id] BIGINT NULL
    CONSTRAINT [FK_order_adjustments_account] FOREIGN KEY REFERENCES [accounts]([id]);
GO

-- Backfill any pre-existing adjustment rows (from Stage 2.6/3, necessarily cash/card-only at
-- that point, since ACCOUNT payments don't exist before this stage) as CASH.
UPDATE [order_adjustments] SET [refund_method] = 'CASH' WHERE [refund_method] IS NULL;
GO

ALTER TABLE [order_adjustments] ALTER COLUMN [refund_method] NVARCHAR(20) NOT NULL;
