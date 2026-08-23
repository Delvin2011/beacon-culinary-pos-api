-- Stage 5.2.4: the direct STOCK_ADMIN waste-entry path now requires an explicit location too —
-- waste_entries itself needs to store it (not just the movement it produces) so GET /admin/waste
-- can surface it without a second lookup. Existing rows predate the concept entirely; they're
-- backfilled to Main Store, consistent with how their corresponding ingredient_stock_movements
-- rows were already backfilled there in Stage 5.2.1 (V61) — this is the same flagged, acceptable
-- historical gap Stage 5.2.1 already established, not a new one.
--
-- Split across GO batches: the backfill UPDATE references the column added just above it, and
-- SQL Server compiles a script as one batch, so a same-batch reference to a brand-new column
-- fails (same issue V35/V41/V61/V64 hit).
ALTER TABLE [waste_entries] ADD [location_id] BIGINT NULL;
GO

UPDATE [waste_entries]
SET [location_id] = (SELECT [id] FROM [locations] WHERE [name] = 'Main Store')
WHERE [location_id] IS NULL;
GO

ALTER TABLE [waste_entries] ALTER COLUMN [location_id] BIGINT NOT NULL;
GO

ALTER TABLE [waste_entries]
    ADD CONSTRAINT [FK_waste_entries_location] FOREIGN KEY ([location_id]) REFERENCES [locations]([id]);
