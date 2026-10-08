-- Reports R1 §1.5: the definitions layer. Every report composes these views and never
-- re-derives a business rule, so each rule below is defined exactly once. Timestamps are
-- stored as UTC DATETIME2; the business calendar is Africa/Johannesburg (no daylight saving).
-- Each view exposes the shift's raw UTC opened_at so callers can filter a trading-day range
-- with a sargable predicate on the shifts(opened_at) index (V83).
-- Split across GO batches: CREATE VIEW must be the only statement in its batch.

-- Trading day of a shift = the Johannesburg calendar date of shifts.opened_at.
CREATE VIEW [v_shift_facts] AS
SELECT
    s.[id]                AS [shift_id],
    s.[cashier_id]        AS [owner_id],
    s.[status]            AS [status],
    s.[opening_float]     AS [opening_float],
    s.[opened_at]         AS [opened_at_utc],
    CAST((s.[opened_at] AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time' AS DATE) AS [trading_day]
FROM [shifts] s;
GO

-- One row per order. Day of sale = the trading day of the order's shift (not the order's own
-- timestamp), so an overnight shift's orders stay on one day; sale_hour is the order's own
-- Johannesburg hour. Reductions are attributed to the order (its day of sale and hour),
-- whichever shift processed them. The four adjustment kinds are mutually exclusive and match
-- the POS activity view: DISCOUNT by action first (discounts are stored with scope WHOLE_ORDER),
-- then EXTRAS_ONLY, then whole-order VOID / REFUND.
-- net_sales = gross - reductions and collected = order_payments are the two figures a future
-- complimentary/staff-meal rule will change, so they live here and nowhere else.
CREATE VIEW [v_order_facts] AS
SELECT
    o.[id]                AS [order_id],
    o.[shift_id]          AS [shift_id],
    sf.[opened_at_utc]    AS [shift_opened_at_utc],
    sf.[trading_day]      AS [day_of_sale],
    DATEPART(HOUR, (o.[created_at] AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time') AS [sale_hour],
    CASE WHEN o.[status] IN ('VOIDED', 'REFUNDED') THEN 1 ELSE 0 END AS [is_voided_or_refunded],
    o.[original_total]    AS [gross_sales],
    o.[total]             AS [order_total],
    COALESCE(adj.[voids], 0)          AS [voids],
    COALESCE(adj.[refunds], 0)        AS [refunds],
    COALESCE(adj.[discounts], 0)      AS [discounts],
    COALESCE(adj.[extras_removed], 0) AS [extras_removed],
    o.[original_total] - COALESCE(adj.[reductions], 0) AS [net_sales],
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
        SUM(CASE WHEN a.[action] = 'DISCOUNT' THEN a.[amount] ELSE 0 END) AS [discounts],
        SUM(CASE WHEN a.[action] <> 'DISCOUNT' AND a.[scope] = 'EXTRAS_ONLY' THEN a.[amount] ELSE 0 END) AS [extras_removed],
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
) pay;
GO

-- One row per adjustment, attributed to the shift that processed it. Day processed = that
-- shift's trading day (the cash attribution used by shift reconciliation). Paid out:
-- refund_method CASH = cash paid out of the drawer; ACCOUNT_BALANCE = an account credit.
CREATE VIEW [v_adjustment_facts] AS
SELECT
    a.[id]                AS [adjustment_id],
    a.[order_id]          AS [order_id],
    a.[shift_id]          AS [processed_shift_id],
    sf.[opened_at_utc]    AS [processed_shift_opened_at_utc],
    sf.[trading_day]      AS [day_processed],
    a.[refund_method]     AS [refund_method],
    a.[amount]            AS [amount],
    CASE WHEN a.[refund_method] = 'CASH' THEN a.[amount] ELSE 0 END            AS [cash_paid_out],
    CASE WHEN a.[refund_method] = 'ACCOUNT_BALANCE' THEN a.[amount] ELSE 0 END AS [account_credits],
    CASE
        WHEN a.[action] = 'DISCOUNT' THEN 'DISCOUNT'
        WHEN a.[scope] = 'EXTRAS_ONLY' THEN 'EXTRAS_REMOVED'
        WHEN a.[action] = 'VOID' THEN 'VOID'
        ELSE 'REFUND'
    END                   AS [adjustment_type]
FROM [order_adjustments] a
JOIN [v_shift_facts] sf ON sf.[shift_id] = a.[shift_id];
