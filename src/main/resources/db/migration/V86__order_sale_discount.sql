-- Discount before payment: a manager-approved discount applied at checkout, so the customer
-- simply pays less. pre_discount_total - sale_discount = original_total (= the sum of the
-- payments, same meaning as before). A discount at sale is not an adjustment: no money is
-- handed back, so it never counts as cash paid out. Split across GO batches: the backfill
-- UPDATE references a column added in the same script (same pattern as V35/V41).
ALTER TABLE [orders] ADD [pre_discount_total] DECIMAL(10, 2) NULL;
ALTER TABLE [orders] ADD [sale_discount] DECIMAL(10, 2) NOT NULL
    CONSTRAINT [DF_orders_sale_discount] DEFAULT 0;
GO

-- Existing orders had no discount at checkout.
UPDATE [orders] SET [pre_discount_total] = [original_total] WHERE [pre_discount_total] IS NULL;
GO

ALTER TABLE [orders] ALTER COLUMN [pre_discount_total] DECIMAL(10, 2) NOT NULL;
GO

-- The discount's details and approval; at most one per order.
CREATE TABLE [order_discounts] (
    [id]             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]       BIGINT         NOT NULL,
    [discount_type]  NVARCHAR(15)   NOT NULL,
    [discount_value] DECIMAL(10, 2) NOT NULL,
    [amount]         DECIMAL(10, 2) NOT NULL,
    [reason_code]    NVARCHAR(30)   NOT NULL,
    [note]           NVARCHAR(255)  NULL,
    [requested_by]   BIGINT         NOT NULL,
    [authorized_by]  BIGINT         NOT NULL,
    [created_at]     DATETIME2      NOT NULL CONSTRAINT [DF_order_discounts_created_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [UQ_order_discounts_order] UNIQUE ([order_id]),
    CONSTRAINT [FK_order_discounts_order] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_order_discounts_requested_by] FOREIGN KEY ([requested_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_order_discounts_authorized_by] FOREIGN KEY ([authorized_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_order_discounts_type] CHECK ([discount_type] IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT [CK_order_discounts_amount] CHECK ([amount] > 0),
    CONSTRAINT [CK_order_discounts_reason_code] CHECK ([reason_code] IN
        ('STAFF', 'PROMOTION', 'MANAGER_GOODWILL', 'PRICING_CORRECTION', 'OTHER'))
);
