-- O4: recording offline sales (docs/2026-11-18-o4-offline-sync-api-contract.md).
--
-- 1. orders: offline fields. recorded_offline marks a sale recorded through sync; offline_receipt_number
--    and collection_number are what the customer's slip shows; sold_at is when it was sold (created_at
--    stays the time it was recorded); offline_grant_id the grant it was made under; offline_trading_day
--    and offline_seq the till's own numbering (unique per till and day while offline_seq is set; a
--    clash is recorded with offline_seq NULL); booked_trading_day the later day a sale is counted on
--    when its own day already had a Z (LATE_AFTER_Z). All NULL / 0 for online sales.
-- 2. order_lines.daily_meal_option_id and order_line_extras.daily_component_stock_id become nullable:
--    an offline line the server can't match (UNKNOWN_ITEM) keeps its name and price without a menu link.
-- 3. ingredient_stock_movements.occurred_at: when the stock was actually used (an offline sale's
--    sold_at); NULL means created_at.
-- 4. sale_exceptions: what managers review (contract section 6). trading_day and device_id are copied
--    from the order for filtering.
-- 5. Report views: an order counts on booked_trading_day when set, otherwise its shift's day as before,
--    and its sale hour is sold_at's when set. Order lines without a menu link appear under their own
--    name. Online history is unchanged (the new columns are NULL for it).
--
-- Split across GO batches: CREATE VIEW must be the only statement in its batch, and new columns must
-- exist before later statements use them.

-- 1. Orders -------------------------------------------------------------------------------------

ALTER TABLE [orders] ADD
    [recorded_offline]        BIT              NOT NULL CONSTRAINT [DF_orders_recorded_offline] DEFAULT 0,
    [offline_receipt_number]  NVARCHAR(30)     NULL,
    [collection_number]       NVARCHAR(20)     NULL,
    [sold_at]                 DATETIME2        NULL,
    [offline_grant_id]        UNIQUEIDENTIFIER NULL CONSTRAINT [FK_orders_offline_grant] REFERENCES [offline_grants] ([id]),
    [offline_trading_day]     DATE             NULL,
    [offline_seq]             INT              NULL,
    [booked_trading_day]      DATE             NULL;
GO

CREATE UNIQUE INDEX [UX_orders_offline_seq] ON [orders] ([device_id], [offline_trading_day], [offline_seq])
    WHERE [offline_seq] IS NOT NULL;
CREATE INDEX [IX_orders_offline_grant] ON [orders] ([offline_grant_id]) WHERE [offline_grant_id] IS NOT NULL;

-- 2. Lines without a menu link ------------------------------------------------------------------

ALTER TABLE [order_lines] ALTER COLUMN [daily_meal_option_id] BIGINT NULL;
ALTER TABLE [order_line_extras] ALTER COLUMN [daily_component_stock_id] BIGINT NULL;

-- 3. Stock movements ----------------------------------------------------------------------------

ALTER TABLE [ingredient_stock_movements] ADD [occurred_at] DATETIME2 NULL;

-- 4. Sale exceptions ----------------------------------------------------------------------------

CREATE TABLE [sale_exceptions] (
    [id]                BIGINT IDENTITY (1, 1) NOT NULL CONSTRAINT [PK_sale_exceptions] PRIMARY KEY,
    [order_id]          BIGINT           NOT NULL CONSTRAINT [FK_sale_exceptions_order] REFERENCES [orders] ([id]),
    [code]              NVARCHAR(40)     NOT NULL,
    [severity]          NVARCHAR(10)     NOT NULL CONSTRAINT [CK_sale_exceptions_severity] CHECK ([severity] IN ('INFO', 'REVIEW')),
    [detail]            NVARCHAR(MAX)    NOT NULL,
    [status]            NVARCHAR(10)     NOT NULL CONSTRAINT [DF_sale_exceptions_status] DEFAULT 'OPEN'
                            CONSTRAINT [CK_sale_exceptions_status] CHECK ([status] IN ('OPEN', 'RESOLVED')),
    [resolution]        NVARCHAR(20)     NULL CONSTRAINT [CK_sale_exceptions_resolution] CHECK ([resolution] IN ('RESOLVED', 'ACCOUNT_APPROVED')),
    [resolution_note]   NVARCHAR(500)    NULL,
    [resolved_by]       BIGINT           NULL,
    [resolved_by_name]  NVARCHAR(255)    NULL,
    [resolved_at]       DATETIME2        NULL,
    [created_at]        DATETIME2        NOT NULL CONSTRAINT [DF_sale_exceptions_created_at] DEFAULT SYSUTCDATETIME(),
    [device_id]         UNIQUEIDENTIFIER NULL,
    [trading_day]       DATE             NOT NULL,
    CONSTRAINT [CK_sale_exceptions_resolved] CHECK (([status] = 'OPEN' AND [resolved_at] IS NULL AND [resolution] IS NULL)
        OR ([status] = 'RESOLVED' AND [resolved_at] IS NOT NULL AND [resolution] IS NOT NULL))
);

CREATE INDEX [IX_sale_exceptions_status_day] ON [sale_exceptions] ([status], [trading_day]);
CREATE INDEX [IX_sale_exceptions_order] ON [sale_exceptions] ([order_id]);
CREATE INDEX [IX_sale_exceptions_device_status] ON [sale_exceptions] ([device_id], [status]);
GO

-- 5. Report views -------------------------------------------------------------------------------

-- As V92, except day_of_sale (booked_trading_day first) and sale_hour (sold_at first).
CREATE OR ALTER VIEW [v_order_facts] AS
SELECT
    o.[id]                AS [order_id],
    o.[shift_id]          AS [shift_id],
    sf.[opened_at_utc]    AS [shift_opened_at_utc],
    COALESCE(o.[booked_trading_day], sf.[trading_day]) AS [day_of_sale],
    DATEPART(HOUR, (COALESCE(o.[sold_at], o.[created_at]) AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time') AS [sale_hour],
    CASE WHEN o.[status] IN ('VOIDED', 'REFUNDED') THEN 1 ELSE 0 END AS [is_voided_or_refunded],
    o.[pre_discount_total] AS [gross_sales],
    o.[sale_discount]      AS [discounts_at_sale],
    o.[total]              AS [order_total],
    COALESCE(adj.[voids], 0)              AS [voids],
    COALESCE(adj.[refunds], 0)            AS [refunds],
    COALESCE(adj.[extras_removed], 0)     AS [extras_removed],
    COALESCE(adj.[legacy_discounts], 0)   AS [legacy_discounts_after_payment],
    COALESCE(adj.[legacy_discount_count], 0) AS [legacy_discount_count],
    o.[pre_discount_total] - o.[sale_discount] - COALESCE(adj.[reductions], 0) AS [net_sales],
    COALESCE(pay.[cash], 0)           AS [cash_collected],
    COALESCE(pay.[card], 0)           AS [card_collected],
    COALESCE(pay.[account], 0)        AS [account_collected],
    COALESCE(pay.[collected], 0)      AS [collected],
    COALESCE(pay.[payment_lines], 0)  AS [payment_lines]
FROM [orders] o
JOIN [v_shift_facts] sf ON sf.[shift_id] = o.[shift_id]
OUTER APPLY (
    SELECT
        SUM(CASE WHEN a.[action] <> 'DISCOUNT' AND a.[scope] = 'WHOLE_ORDER' AND a.[action] = 'VOID'   THEN a.[amount] ELSE 0 END) AS [voids],
        SUM(CASE WHEN a.[action] <> 'DISCOUNT' AND a.[scope] = 'WHOLE_ORDER' AND a.[action] = 'REFUND' THEN a.[amount] ELSE 0 END) AS [refunds],
        SUM(CASE WHEN a.[action] <> 'DISCOUNT' AND a.[scope] = 'EXTRAS_ONLY' THEN a.[amount] ELSE 0 END) AS [extras_removed],
        SUM(CASE WHEN a.[action] = 'DISCOUNT' THEN a.[amount] ELSE 0 END) AS [legacy_discounts],
        SUM(CASE WHEN a.[action] = 'DISCOUNT' THEN 1 ELSE 0 END) AS [legacy_discount_count],
        SUM(a.[amount]) AS [reductions]
    FROM [order_adjustments] a
    WHERE a.[order_id] = o.[id]
) adj
OUTER APPLY (
    SELECT
        SUM(CASE WHEN p.[method] = 'CASH'    THEN p.[amount] ELSE 0 END) AS [cash],
        SUM(CASE WHEN p.[method] = 'CARD'    THEN p.[amount] ELSE 0 END) AS [card],
        SUM(CASE WHEN p.[method] = 'ACCOUNT' THEN p.[amount] ELSE 0 END) AS [account],
        SUM(p.[amount]) AS [collected],
        COUNT(*)        AS [payment_lines]
    FROM [order_payments] p
    WHERE p.[order_id] = o.[id]
) pay
WHERE o.[order_kind] = 'SALE';
GO

-- As V92, except trading_day (booked_trading_day first) and lines without a menu link, which keep
-- their own name (catalog_id and meal_period_id NULL).
CREATE OR ALTER VIEW [v_order_line_facts] AS
SELECT
    'MEAL'                    AS [item_type],
    dmo.[meal_catalog_id]     AS [catalog_id],
    COALESCE(mc.[name], ol.[name]) AS [item_name],
    dmo.[meal_period_id]      AS [meal_period_id],
    CAST(NULL AS NVARCHAR(50)) AS [plu_code],
    o.[id]                    AS [order_id],
    sf.[shift_id]             AS [shift_id],
    sf.[opened_at_utc]        AS [shift_opened_at_utc],
    COALESCE(o.[booked_trading_day], sf.[trading_day]) AS [trading_day],
    ol.[quantity]             AS [quantity],
    ol.[unit_price]           AS [unit_price],
    ol.[unit_price] * ol.[quantity] AS [gross],
    CASE
        WHEN o.[order_kind] = 'COMPLIMENTARY' THEN 'COMPLIMENTARY'
        WHEN o.[status] = 'VOIDED' THEN 'VOIDED'
        WHEN o.[status] = 'REFUNDED' THEN 'REFUNDED_OR_REMOVED'
        ELSE 'NET'
    END                       AS [unit_bucket]
FROM [order_lines] ol
JOIN [orders] o              ON o.[id] = ol.[order_id]
JOIN [v_shift_facts] sf      ON sf.[shift_id] = o.[shift_id]
LEFT JOIN [daily_meal_options] dmo ON dmo.[id] = ol.[daily_meal_option_id]
LEFT JOIN [meal_catalog] mc       ON mc.[id] = dmo.[meal_catalog_id]
WHERE NOT (o.[order_kind] = 'COMPLIMENTARY' AND o.[status] = 'VOIDED')
UNION ALL
SELECT
    'EXTRA',
    dcs.[component_catalog_id],
    COALESCE(cc.[name], e.[component_name]),
    dcs.[meal_period_id],
    CAST(NULL AS NVARCHAR(50)),
    o.[id],
    sf.[shift_id],
    sf.[opened_at_utc],
    COALESCE(o.[booked_trading_day], sf.[trading_day]),
    e.[quantity],
    e.[price_delta],
    e.[price_delta] * e.[quantity],
    CASE
        WHEN o.[order_kind] = 'COMPLIMENTARY' THEN 'COMPLIMENTARY'
        WHEN o.[status] = 'VOIDED' THEN 'VOIDED'
        WHEN o.[status] = 'REFUNDED' OR e.[adjusted] = 1 THEN 'REFUNDED_OR_REMOVED'
        ELSE 'NET'
    END
FROM [order_line_extras] e
JOIN [order_lines] ol                 ON ol.[id] = e.[order_line_id]
JOIN [orders] o                       ON o.[id] = ol.[order_id]
JOIN [v_shift_facts] sf               ON sf.[shift_id] = o.[shift_id]
LEFT JOIN [daily_component_stock] dcs ON dcs.[id] = e.[daily_component_stock_id]
LEFT JOIN [component_catalog] cc      ON cc.[id] = dcs.[component_catalog_id]
WHERE NOT (o.[order_kind] = 'COMPLIMENTARY' AND o.[status] = 'VOIDED');
GO
