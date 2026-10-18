-- Complimentary meals: a manager-approved order with no payment. It goes to the kitchen, gets a
-- daily order number and uses stock like any order, but no money moves. Money fields:
--   SALE:          pre_discount_total − sale_discount = original_total (= Σ payments) − adjustments = total
--   COMPLIMENTARY: pre_discount_total = list value; sale_discount = original_total = total = 0; no payments
-- Split across GO batches: CREATE VIEW must be the only statement in its batch.
ALTER TABLE [orders] ADD [order_kind] NVARCHAR(15) NOT NULL
    CONSTRAINT [DF_orders_order_kind] DEFAULT 'SALE'
    CONSTRAINT [CK_orders_order_kind] CHECK ([order_kind] IN ('SALE', 'COMPLIMENTARY'));
GO

-- The reason and approval; exactly one per complimentary order.
CREATE TABLE [order_complimentaries] (
    [id]            BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]      BIGINT         NOT NULL,
    [reason_code]   NVARCHAR(20)   NOT NULL,
    [note]          NVARCHAR(255)  NULL,
    [requested_by]  BIGINT         NOT NULL,
    [authorized_by] BIGINT         NOT NULL,
    [created_at]    DATETIME2      NOT NULL CONSTRAINT [DF_order_complimentaries_created_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [UQ_order_complimentaries_order] UNIQUE ([order_id]),
    CONSTRAINT [FK_order_complimentaries_order] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_order_complimentaries_requested_by] FOREIGN KEY ([requested_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_order_complimentaries_authorized_by] FOREIGN KEY ([authorized_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_order_complimentaries_reason_code] CHECK ([reason_code] IN
        ('STAFF_MEAL', 'MANAGEMENT', 'GUEST', 'TASTING', 'OTHER'))
);
GO

-- Sales orders only (order_kind = SALE): the one place every R1 sales, tender and net-sales
-- figure gets its orders from, so complimentary orders change none of them. Otherwise
-- identical to V87.
CREATE OR ALTER VIEW [v_order_facts] AS
SELECT
    o.[id]                AS [order_id],
    o.[shift_id]          AS [shift_id],
    sf.[opened_at_utc]    AS [shift_opened_at_utc],
    sf.[trading_day]      AS [day_of_sale],
    DATEPART(HOUR, (o.[created_at] AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time') AS [sale_hour],
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

-- Complimentary meals: one row per complimentary order that wasn't voided, on its day of sale
-- (its shift's trading day) and in the shift it was rung in. value = the list value
-- (pre_discount_total). Outside the sales and net-sales figures.
CREATE OR ALTER VIEW [v_complimentary_facts] AS
SELECT
    o.[id]                 AS [order_id],
    o.[shift_id]           AS [shift_id],
    sf.[opened_at_utc]     AS [shift_opened_at_utc],
    sf.[trading_day]       AS [day_of_sale],
    DATEPART(HOUR, (o.[created_at] AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time') AS [sale_hour],
    c.[reason_code]        AS [reason_code],
    o.[pre_discount_total] AS [list_value]
FROM [orders] o
JOIN [v_shift_facts] sf ON sf.[shift_id] = o.[shift_id]
LEFT JOIN [order_complimentaries] c ON c.[order_id] = o.[id]
WHERE o.[order_kind] = 'COMPLIMENTARY' AND o.[status] <> 'VOIDED';
GO

-- Reports R2 (V91) line facts, with complimentary orders in their own bucket:
--   COMPLIMENTARY  a line on a complimentary order (checked first). Voided complimentary
--                  orders are left out entirely: nothing was given away.
-- The other buckets are unchanged and now cover sales orders only, so gross as rung still
-- agrees with Sales Summary's gross sales.
CREATE OR ALTER VIEW [v_order_line_facts] AS
SELECT
    'MEAL'                    AS [item_type],
    dmo.[meal_catalog_id]     AS [catalog_id],
    mc.[name]                 AS [item_name],
    dmo.[meal_period_id]      AS [meal_period_id],
    CAST(NULL AS NVARCHAR(50)) AS [plu_code],
    o.[id]                    AS [order_id],
    sf.[shift_id]             AS [shift_id],
    sf.[opened_at_utc]        AS [shift_opened_at_utc],
    sf.[trading_day]          AS [trading_day],
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
JOIN [daily_meal_options] dmo ON dmo.[id] = ol.[daily_meal_option_id]
JOIN [meal_catalog] mc       ON mc.[id] = dmo.[meal_catalog_id]
WHERE NOT (o.[order_kind] = 'COMPLIMENTARY' AND o.[status] = 'VOIDED')
UNION ALL
SELECT
    'EXTRA',
    dcs.[component_catalog_id],
    cc.[name],
    dcs.[meal_period_id],
    CAST(NULL AS NVARCHAR(50)),
    o.[id],
    sf.[shift_id],
    sf.[opened_at_utc],
    sf.[trading_day],
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
JOIN [order_lines] ol            ON ol.[id] = e.[order_line_id]
JOIN [orders] o                  ON o.[id] = ol.[order_id]
JOIN [v_shift_facts] sf          ON sf.[shift_id] = o.[shift_id]
JOIN [daily_component_stock] dcs ON dcs.[id] = e.[daily_component_stock_id]
JOIN [component_catalog] cc      ON cc.[id] = dcs.[component_catalog_id]
WHERE NOT (o.[order_kind] = 'COMPLIMENTARY' AND o.[status] = 'VOIDED');
