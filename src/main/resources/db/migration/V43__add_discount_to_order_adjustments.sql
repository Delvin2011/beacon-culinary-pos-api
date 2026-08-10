-- Stage 3.2: discounts share the order_adjustments table with Stage 2.6's void/refund rows,
-- categorized separately via a widened action CHECK plus two new columns that only apply to
-- DISCOUNT rows. The action CHECK was explicitly named in V37, so no lookup needed here.
ALTER TABLE [order_adjustments] DROP CONSTRAINT [CK_order_adjustments_action];

ALTER TABLE [order_adjustments] WITH CHECK ADD CONSTRAINT [CK_order_adjustments_action]
    CHECK ([action] IN ('VOID', 'REFUND', 'DISCOUNT'));

ALTER TABLE [order_adjustments] ADD [discount_type] NVARCHAR(20) NULL
    CONSTRAINT [CK_order_adjustments_discount_type] CHECK ([discount_type] IN ('PERCENTAGE', 'FIXED_AMOUNT'));
ALTER TABLE [order_adjustments] ADD [discount_value] DECIMAL(10, 2) NULL;
