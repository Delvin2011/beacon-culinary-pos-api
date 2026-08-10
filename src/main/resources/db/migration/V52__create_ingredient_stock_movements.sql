-- Stage 5: the single append-only ledger backing every raw-ingredient stock figure in this
-- system — current stock is always SUM(quantity), never a stored/mutable field, consistent with
-- every other ledger here (order_status_events, order_adjustments, account_payments).
-- source_type/source_id point at whichever record caused the movement: a Grv (V53), a
-- WasteEntry (V54), a StockTake (V55), or an IngredientRequirementConfirmation (V56) — a
-- polymorphic reference, so it is deliberately not an FK.
CREATE TABLE [ingredient_stock_movements] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [ingredient_id]   BIGINT NOT NULL,
    [movement_type]   NVARCHAR(30)   NOT NULL,
    [quantity]        DECIMAL(10, 4) NOT NULL,
    [cost_per_unit]   DECIMAL(10, 2) NULL,
    [source_type]     NVARCHAR(30)   NOT NULL,
    [source_id]       BIGINT         NOT NULL,
    [recorded_by]     BIGINT         NOT NULL,
    [created_at]      DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_ingredient_stock_movements_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [FK_ingredient_stock_movements_recorded_by] FOREIGN KEY ([recorded_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_ingredient_stock_movements_movement_type] CHECK ([movement_type] IN
        ('RECEIVED', 'CONSUMED_FOR_PREP', 'WASTED', 'STOCK_TAKE_ADJUSTMENT')),
    CONSTRAINT [CK_ingredient_stock_movements_source_type] CHECK ([source_type] IN
        ('GRV', 'DAILY_PLANNING_CONFIRMATION', 'WASTE_ENTRY', 'STOCK_TAKE'))
);

CREATE INDEX [IX_ingredient_stock_movements_ingredient_id] ON [ingredient_stock_movements]([ingredient_id]);
