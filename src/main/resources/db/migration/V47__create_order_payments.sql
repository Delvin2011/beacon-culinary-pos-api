-- Stage 4 Part A: an order's payment(s) — 1 entry for a single-method order, up to 2 for a
-- CASH+CARD split, always exactly 1 for ACCOUNT (mutually exclusive with CASH/CARD, enforced in
-- application code, not here). Replaces orders.payment_method/amount_tendered/change_due/
-- card_reference (Stage 1.3/3) as the source of truth going forward; those columns are left in
-- place, unused, for a future cleanup.
CREATE TABLE [order_payments] (
    [id]               BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]         BIGINT         NOT NULL,
    [method]           NVARCHAR(10)   NOT NULL,
    [amount]           DECIMAL(10, 2) NOT NULL CHECK ([amount] > 0),
    [amount_tendered]  DECIMAL(10, 2) NULL,
    [change_due]       DECIMAL(10, 2) NULL,
    [card_reference]   NVARCHAR(50)   NULL,
    [account_id]       BIGINT         NULL,
    CONSTRAINT [FK_order_payments_order] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_order_payments_account] FOREIGN KEY ([account_id]) REFERENCES [accounts]([id]),
    CONSTRAINT [CK_order_payments_method] CHECK ([method] IN ('CASH', 'CARD', 'ACCOUNT'))
);

CREATE INDEX [IX_order_payments_order_id] ON [order_payments]([order_id]);

-- Backfill existing Stage 1.3/3 orders (necessarily single-method, CASH or CARD only, since
-- ACCOUNT didn't exist before this stage) as single-entry rows. Uses original_total rather than
-- the spec snippet's literal "total" — total may have already been reduced by a Stage 2.6/3
-- adjustment, and this column must reflect what was actually collected at sale time (the same
-- reasoning Stage 2.5 used original_total for, in the formula this table now replaces).
INSERT INTO [order_payments] ([order_id], [method], [amount], [amount_tendered], [change_due], [card_reference])
SELECT [id], [payment_method], [original_total], [amount_tendered], [change_due], [card_reference]
FROM [orders]
WHERE [payment_method] IN ('CASH', 'CARD');
