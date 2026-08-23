-- Stage 5.2.2: GRV restructures from one-row-per-ingredient to header + lines, matching the real
-- delivery template (one invoice number, one supplier, covering however many items that invoice
-- actually contained). Existing single-line rows become one-line grv_lines; their stock
-- movements are re-pointed from the old header id to the new line id so source_id always traces
-- to the specific line item that produced it, not just the delivery it was part of.
--
-- Split across GO batches throughout: several statements reference a column added earlier in
-- this same script, and SQL Server compiles a script as one batch, so a same-batch reference to
-- a brand-new column fails (same issue V35/V41/V61 hit).
CREATE TABLE [grv_lines] (
    [id]                     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [grv_id]                 BIGINT         NOT NULL,
    [ingredient_id]          BIGINT         NOT NULL,
    [purchase_order_line_id] BIGINT         NULL,
    [quantity_ordered]       DECIMAL(10, 4) NULL,
    [quantity_received]      DECIMAL(10, 4) NOT NULL,
    [cost_per_unit]          DECIMAL(10, 2) NOT NULL,
    CONSTRAINT [FK_grv_lines_grv] FOREIGN KEY ([grv_id]) REFERENCES [grv]([id]),
    CONSTRAINT [FK_grv_lines_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [FK_grv_lines_purchase_order_line] FOREIGN KEY ([purchase_order_line_id]) REFERENCES [purchase_order_lines]([id]),
    CONSTRAINT [CK_grv_lines_quantity_received] CHECK ([quantity_received] > 0)
);

CREATE INDEX [IX_grv_lines_grv_id] ON [grv_lines]([grv_id]);
CREATE INDEX [IX_grv_lines_ingredient_id] ON [grv_lines]([ingredient_id]);
GO

-- Migrate existing single-line GRV rows into grv_lines, capturing the old-header-id ->
-- new-line-id mapping so existing stock movements can be re-pointed correctly.
DECLARE @Mapping TABLE (old_grv_id BIGINT, new_grv_line_id BIGINT);

INSERT INTO [grv_lines] ([grv_id], [ingredient_id], [quantity_received], [cost_per_unit])
OUTPUT inserted.[grv_id], inserted.[id] INTO @Mapping
SELECT [id], [ingredient_id], [quantity], [cost_per_unit] FROM [grv];

UPDATE m
SET m.[source_id] = map.new_grv_line_id
FROM [ingredient_stock_movements] m
JOIN @Mapping map ON m.[source_type] = 'GRV' AND m.[source_id] = map.old_grv_id;
GO

-- Now safe to drop the now-redundant line-shaped columns (and their constraints/index) from the
-- header table.
DROP INDEX [IX_grv_ingredient_id] ON [grv];
ALTER TABLE [grv] DROP CONSTRAINT [FK_grv_ingredient];
ALTER TABLE [grv] DROP CONSTRAINT [CK_grv_quantity];
ALTER TABLE [grv] DROP COLUMN [ingredient_id], [quantity], [cost_per_unit];
GO

-- New header-level fields. invoice_number is backfilled for legacy rows and then locked
-- NOT NULL — every new GRV requires a real one at the application level; even an ad-hoc
-- purchase (no linked purchase order) still has a real supplier invoice.
ALTER TABLE [grv] ADD [invoice_number] NVARCHAR(50) NULL;
GO

UPDATE [grv] SET [invoice_number] = 'LEGACY-UNKNOWN' WHERE [invoice_number] IS NULL;
GO

ALTER TABLE [grv] ALTER COLUMN [invoice_number] NVARCHAR(50) NOT NULL;
GO

ALTER TABLE [grv] ADD [purchase_order_id] BIGINT NULL;
GO

ALTER TABLE [grv] ADD CONSTRAINT [FK_grv_purchase_order] FOREIGN KEY ([purchase_order_id]) REFERENCES [purchase_orders]([id]);
