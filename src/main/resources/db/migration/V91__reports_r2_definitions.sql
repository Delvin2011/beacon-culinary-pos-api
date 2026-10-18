-- Reports R2 §2: three more shared definitions, each defined once here and composed by the
-- reports (never re-derived). Read-only views over existing tables; R1's views are unchanged.
-- Same conventions as V85: UTC DATETIME2 storage, Johannesburg business calendar, and each view
-- exposes a raw UTC instant whose Johannesburg date is the row's day, so a trading-day range is
-- a half-open UTC window.
-- Split across GO batches: CREATE VIEW must be the only statement in its batch.

-- A. One row per meal line and per extras line, on the order's day of sale (the trading day of
-- the order's shift). gross = unit_price × quantity, the same arithmetic order creation uses, so
-- an order's meal lines plus extras always add up to its pre_discount_total. Item revenue is
-- before discounts: a discount at sale belongs to the whole order and is never spread here.
-- unit_bucket puts every unit in exactly one bucket. A line's units always share a bucket:
-- removing extras takes every remaining extra at its full quantity (order_line_extras.adjusted).
--   VOIDED               the order is VOIDED (wins over everything else)
--   REFUNDED_OR_REMOVED  the order is REFUNDED, or the extra was removed (adjusted = 1), whether
--                        the removal was a void (order still PENDING) or a refund. An extra
--                        removed and later refunded with its order is counted here once.
--   NET                  otherwise
-- item_name is the catalog's current name, so a renamed item keeps its history on one row.
-- plu_code: the catalog has no PLU column yet; NULL until it does.
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
        WHEN o.[status] = 'VOIDED' THEN 'VOIDED'
        WHEN o.[status] = 'REFUNDED' THEN 'REFUNDED_OR_REMOVED'
        ELSE 'NET'
    END                       AS [unit_bucket]
FROM [order_lines] ol
JOIN [orders] o              ON o.[id] = ol.[order_id]
JOIN [v_shift_facts] sf      ON sf.[shift_id] = o.[shift_id]
JOIN [daily_meal_options] dmo ON dmo.[id] = ol.[daily_meal_option_id]
JOIN [meal_catalog] mc       ON mc.[id] = dmo.[meal_catalog_id]
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
        WHEN o.[status] = 'VOIDED' THEN 'VOIDED'
        WHEN o.[status] = 'REFUNDED' OR e.[adjusted] = 1 THEN 'REFUNDED_OR_REMOVED'
        ELSE 'NET'
    END
FROM [order_line_extras] e
JOIN [order_lines] ol            ON ol.[id] = e.[order_line_id]
JOIN [orders] o                  ON o.[id] = ol.[order_id]
JOIN [v_shift_facts] sf          ON sf.[shift_id] = o.[shift_id]
JOIN [daily_component_stock] dcs ON dcs.[id] = e.[daily_component_stock_id]
JOIN [component_catalog] cc      ON cc.[id] = dcs.[component_catalog_id];
GO

-- B. What was offered: one row per plan entry. Meals from the daily meal options, extras from
-- the daily component stock. Every plan entry counts, whatever its status ("on the daily plan").
-- An item can appear more than once on a date; callers count DISTINCT plan_date.
CREATE OR ALTER VIEW [v_offered_items] AS
SELECT
    'MEAL'                AS [item_type],
    [meal_catalog_id]     AS [catalog_id],
    [meal_period_id]      AS [meal_period_id],
    CAST(NULL AS NVARCHAR(50)) AS [plu_code],
    [option_date]         AS [plan_date]
FROM [daily_meal_options]
UNION ALL
SELECT
    'EXTRA',
    [component_catalog_id],
    [meal_period_id],
    CAST(NULL AS NVARCHAR(50)),
    [option_date]
FROM [daily_component_stock];
GO

-- C. Account movements: one row per movement, the same three terms the account screen's balance
-- uses (AccountService: charged − reversed − paid). Accounts carry no opening balance and no
-- credit limit, so the movements are the whole ledger.
--   CHARGE   an ACCOUNT payment on an order          entry_day = day of sale
--   CREDIT   an adjustment paid out ACCOUNT_BALANCE  entry_day = day processed
--            (voids, refunds, extras removed and legacy after-payment discounts alike)
--   PAYMENT  a settlement received (account_payments) entry_day = its Johannesburg date
-- entry_at_utc is the instant whose Johannesburg date is entry_day (the shift's opened_at for
-- CHARGE/CREDIT, the payment's own time for PAYMENT); occurred_at is when it actually happened.
-- Settlements have no method or reference column, so reference is "Payment #id" and the
-- description is the payment's note.
CREATE OR ALTER VIEW [v_account_movements] AS
SELECT
    'CHARGE'              AS [movement_type],
    p.[id]                AS [source_id],
    p.[account_id]        AS [account_id],
    p.[amount]            AS [amount],
    sf.[trading_day]      AS [entry_day],
    sf.[opened_at_utc]    AS [entry_at_utc],
    o.[created_at]        AS [occurred_at],
    o.[id]                AS [order_id],
    CONCAT(N'Order #', o.[order_number]) AS [reference],
    CAST(N'Order paid on account' AS NVARCHAR(255)) AS [description]
FROM [order_payments] p
JOIN [orders] o          ON o.[id] = p.[order_id]
JOIN [v_shift_facts] sf  ON sf.[shift_id] = o.[shift_id]
WHERE p.[method] = 'ACCOUNT' AND p.[account_id] IS NOT NULL
UNION ALL
SELECT
    'CREDIT',
    a.[id],
    a.[account_id],
    a.[amount],
    af.[day_processed],
    af.[processed_shift_opened_at_utc],
    a.[created_at],
    o.[id],
    CONCAT(N'Order #', o.[order_number]),
    CAST(CASE af.[adjustment_type]
        WHEN 'VOID' THEN N'Order voided'
        WHEN 'REFUND' THEN N'Order refunded'
        WHEN 'EXTRAS_REMOVED' THEN N'Extras removed'
        ELSE N'Discount after payment (legacy)'
    END AS NVARCHAR(255))
FROM [order_adjustments] a
JOIN [v_adjustment_facts] af ON af.[adjustment_id] = a.[id]
JOIN [orders] o              ON o.[id] = a.[order_id]
WHERE a.[refund_method] = 'ACCOUNT_BALANCE' AND a.[account_id] IS NOT NULL
UNION ALL
SELECT
    'PAYMENT',
    ap.[id],
    ap.[account_id],
    ap.[amount],
    CAST((ap.[created_at] AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time' AS DATE),
    ap.[created_at],
    ap.[created_at],
    NULL,
    CONCAT(N'Payment #', ap.[id]),
    CAST(COALESCE(NULLIF(ap.[note], N''), N'Payment received') AS NVARCHAR(255))
FROM [account_payments] ap;
