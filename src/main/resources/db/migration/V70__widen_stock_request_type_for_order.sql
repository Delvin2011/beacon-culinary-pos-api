-- Stage 5.2.3: ORDER completes the request_type set alongside ISSUE and WASTE. Unlike those two,
-- approving an ORDER request doesn't move stock — it produces a PurchaseOrder ready for a future
-- GRV to be received against it.
ALTER TABLE [stock_requests] DROP CONSTRAINT [CK_stock_requests_request_type];

ALTER TABLE [stock_requests] ADD CONSTRAINT [CK_stock_requests_request_type]
    CHECK ([request_type] IN ('ISSUE', 'WASTE', 'ORDER'));
