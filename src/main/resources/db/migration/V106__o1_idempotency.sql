-- O1: duplicate-safe sales and adjustments. A till sends a client-generated UUID with each sale
-- (client_order_id) and each void/refund (client_request_id); sending the same one again returns
-- the existing record instead of creating a second. client_request_hash is the SHA-256 of the
-- request's normalised payload (orders.RequestFingerprint), so a reused id with a different
-- payload is refused. Both columns are NULL on rows created without an id (and on all older rows).
-- Filtered unique indexes: any number of NULLs, each non-NULL id at most once.

ALTER TABLE [orders] ADD
    [client_order_id]     UNIQUEIDENTIFIER NULL,
    [client_request_hash] VARBINARY(32)    NULL;
GO

CREATE UNIQUE INDEX [UX_orders_client_order_id] ON [orders]([client_order_id])
    WHERE [client_order_id] IS NOT NULL;
GO

ALTER TABLE [order_adjustments] ADD
    [client_request_id]   UNIQUEIDENTIFIER NULL,
    [client_request_hash] VARBINARY(32)    NULL;
GO

CREATE UNIQUE INDEX [UX_order_adjustments_client_request_id] ON [order_adjustments]([client_request_id])
    WHERE [client_request_id] IS NOT NULL;
