-- Light meal stock: per-meal low-stock level, and per-sale deduction of light meals' ingredients
-- at the Kitchen. A sale writes CONSUMED movements with source ORDER_LINE (source_id =
-- order_lines.id); a void writes CONSUMPTION_REVERSED with the same source, at most once per line
-- and ingredient. Set meals still deduct through planning (DAILY_PLANNING_CONFIRMATION /
-- STOCK_REQUEST) and are untouched. Movement quantities are already DECIMAL(10, 4) (V52).

ALTER TABLE [meal_catalog] ADD [low_stock_units] INT NOT NULL
    CONSTRAINT [DF_meal_catalog_low_stock_units] DEFAULT 10
    CONSTRAINT [CK_meal_catalog_low_stock_units] CHECK ([low_stock_units] BETWEEN 0 AND 10000);

ALTER TABLE [ingredient_stock_movements] DROP CONSTRAINT [CK_ingredient_stock_movements_movement_type];
ALTER TABLE [ingredient_stock_movements]
    ADD CONSTRAINT [CK_ingredient_stock_movements_movement_type] CHECK ([movement_type] IN
        ('RECEIVED', 'ISSUED', 'CONSUMED', 'WASTED', 'STOCK_TAKE_ADJUSTMENT', 'CONSUMPTION_REVERSED'));

ALTER TABLE [ingredient_stock_movements] DROP CONSTRAINT [CK_ingredient_stock_movements_source_type];
ALTER TABLE [ingredient_stock_movements] ADD CONSTRAINT [CK_ingredient_stock_movements_source_type]
    CHECK ([source_type] IN ('GRV', 'DAILY_PLANNING_CONFIRMATION', 'DIRECT_WASTE', 'STOCK_TAKE', 'STOCK_REQUEST', 'ORDER_LINE'));

-- A line is reversed at most once per ingredient.
CREATE UNIQUE INDEX [UQ_ingredient_stock_movements_line_reversal]
    ON [ingredient_stock_movements]([source_type], [source_id], [ingredient_id])
    WHERE [movement_type] = 'CONSUMPTION_REVERSED';
CREATE INDEX [IX_ingredient_stock_movements_source] ON [ingredient_stock_movements]([source_type], [source_id]);
