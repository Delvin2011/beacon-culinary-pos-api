-- Discount before payment (Reports R1 Amendment 1): redefine the order facts once, here.
--   gross_sales                 = pre_discount_total (what the order would have cost undiscounted)
--   discounts_at_sale           = sale_discount (given at checkout; never paid out)
--   reductions after payment    = voids + refunds + extras removed + legacy after-payment discounts
--   net_sales                   = gross - discounts at sale - reductions after payment (= orders.total)
--   collected                   = Σ order_payments (= original_total = gross - discounts at sale)
-- Legacy DISCOUNT adjustments (the retired after-payment discount) keep their own column and a
-- count, so reports can show them only when a range contains any.
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
) pay;
