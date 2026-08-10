-- Stage 3.1: card-present payment support. The CHECK/DEFAULT on payment_method were created
-- inline (unnamed) in V23, so look up the generated constraint name instead of guessing it,
-- same approach V30 used for orders.status.
DECLARE @checkName NVARCHAR(200);

SELECT @checkName = cc.name
FROM sys.check_constraints cc
JOIN sys.columns c ON c.object_id = cc.parent_object_id AND c.column_id = cc.parent_column_id
WHERE cc.parent_object_id = OBJECT_ID('orders') AND c.name = 'payment_method';

EXEC('ALTER TABLE [orders] DROP CONSTRAINT [' + @checkName + ']');

ALTER TABLE [orders] WITH CHECK ADD CONSTRAINT [CK_orders_payment_method]
    CHECK ([payment_method] IN ('CASH', 'CARD'));

-- CARD orders have no tender/change concept — always the exact total — so both become nullable.
ALTER TABLE [orders] ALTER COLUMN [amount_tendered] DECIMAL(10, 2) NULL;
ALTER TABLE [orders] ALTER COLUMN [change_due] DECIMAL(10, 2) NULL;

ALTER TABLE [orders] ADD [card_reference] NVARCHAR(50) NULL;
