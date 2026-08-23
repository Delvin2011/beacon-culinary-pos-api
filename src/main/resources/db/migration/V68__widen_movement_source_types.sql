-- Stage 5 Revision: relabel old direct-waste rows to DIRECT_WASTE, distinguishing a STOCK_ADMIN's
-- own self-logged write-off from one that went through the STOCK_CLERK request-then-authorize
-- path (STOCK_REQUEST, new below) — both write the same WASTED movement shape, only source_type
-- differs now.
UPDATE [ingredient_stock_movements] SET [source_type] = 'DIRECT_WASTE' WHERE [source_type] = 'WASTE_ENTRY';
GO

ALTER TABLE [ingredient_stock_movements] DROP CONSTRAINT [CK_ingredient_stock_movements_source_type];

ALTER TABLE [ingredient_stock_movements] ADD CONSTRAINT [CK_ingredient_stock_movements_source_type]
    CHECK ([source_type] IN ('GRV', 'DAILY_PLANNING_CONFIRMATION', 'DIRECT_WASTE', 'STOCK_TAKE', 'STOCK_REQUEST'));
