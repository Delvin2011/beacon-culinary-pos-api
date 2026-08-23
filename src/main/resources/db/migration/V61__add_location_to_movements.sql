-- Every existing movement predates the location concept and represents stock physically held at
-- Main Store (the only place GRV/Waste/Stock Take have ever written to) — backfilled accordingly
-- before the column is locked down to NOT NULL, so "how much do we have" always requires naming
-- a location going forward. Split across GO batches: the backfill UPDATE references the column
-- added just above it, and SQL Server compiles a script as one batch, so referencing a
-- same-batch new column fails (same issue V35/V41 hit in Stage 2.5/2.6).
ALTER TABLE [ingredient_stock_movements] ADD [location_id] BIGINT NULL;
GO

UPDATE [ingredient_stock_movements]
SET [location_id] = (SELECT [id] FROM [locations] WHERE [name] = 'Main Store')
WHERE [location_id] IS NULL;
GO

ALTER TABLE [ingredient_stock_movements] ALTER COLUMN [location_id] BIGINT NOT NULL;
GO

ALTER TABLE [ingredient_stock_movements]
    ADD CONSTRAINT [FK_ingredient_stock_movements_location] FOREIGN KEY ([location_id]) REFERENCES [locations]([id]);

CREATE INDEX [IX_ingredient_stock_movements_location_id] ON [ingredient_stock_movements]([location_id]);
