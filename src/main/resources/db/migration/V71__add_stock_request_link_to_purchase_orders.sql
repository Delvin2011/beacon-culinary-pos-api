-- Stage 5.2.3: traceability link back to the ORDER-type StockRequest an approved Order Sheet
-- produced this purchase order from. NULL for the existing, unchanged path — an admin creating a
-- purchase order directly. At most one purchase order per stock request (a UNIQUE index, not just
-- an FK), since an ORDER request is approved as a whole, exactly once, into exactly one PO.
ALTER TABLE [purchase_orders] ADD [stock_request_id] BIGINT NULL;
GO

ALTER TABLE [purchase_orders]
    ADD CONSTRAINT [FK_purchase_orders_stock_request] FOREIGN KEY ([stock_request_id]) REFERENCES [stock_requests]([id]);

CREATE UNIQUE INDEX [UX_purchase_orders_stock_request_id] ON [purchase_orders]([stock_request_id]) WHERE [stock_request_id] IS NOT NULL;
