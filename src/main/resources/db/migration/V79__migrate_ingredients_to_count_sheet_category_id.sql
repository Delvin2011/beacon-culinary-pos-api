-- Swaps ingredients.count_sheet_category (a NVARCHAR restricted to the old PREP/BULK/DRYSTOCK/
-- FVEG enum) for a FK into the new count_sheet_categories table (V78).
--
-- The old enum's DRYSTOCK and FVEG values map 1:1 to the new table by name. BULK and PREP have no
-- equivalent in the new taxonomy: BULK is split into POULTRY/MEAT by ingredient name (chicken/
-- turkey/duck items go to POULTRY, everything else BULK-categorized goes to MEAT — this correctly
-- places every ingredient seeded by V58/V59, e.g. Chicken -> POULTRY, Beef/Pork/Sirloin -> MEAT);
-- PREP has zero rows in every seeded environment but is mapped to DRYSTOCK as a safe fallback in
-- case any environment created a PREP-categorized ingredient by hand.
--
-- Split across GO batches: the backfill UPDATE (and later ALTER COLUMN/DROP COLUMN) reference a
-- column added/changed just above, and SQL Server compiles a script as one batch, so a same-batch
-- reference to a brand-new/just-altered column fails (same issue V35/V41/V61/V64/V73 hit).
ALTER TABLE [ingredients] ADD [count_sheet_category_id] BIGINT NULL;
GO

UPDATE i
SET i.[count_sheet_category_id] = c.[id]
FROM [ingredients] i
INNER JOIN [count_sheet_categories] c
    ON c.[name] = CASE
        WHEN i.[count_sheet_category] = 'BULK' AND (
            i.[name] LIKE '%chicken%' OR i.[name] LIKE '%turkey%' OR i.[name] LIKE '%duck%'
        ) THEN 'POULTRY'
        WHEN i.[count_sheet_category] = 'BULK' THEN 'MEAT'
        WHEN i.[count_sheet_category] = 'PREP' THEN 'DRYSTOCK'
        ELSE i.[count_sheet_category]
    END;
GO

ALTER TABLE [ingredients] ALTER COLUMN [count_sheet_category_id] BIGINT NOT NULL;
GO

ALTER TABLE [ingredients] ADD CONSTRAINT [FK_ingredients_count_sheet_category]
    FOREIGN KEY ([count_sheet_category_id]) REFERENCES [count_sheet_categories] ([id]);
GO

ALTER TABLE [ingredients] DROP CONSTRAINT [CK_ingredients_count_sheet_category];
GO

ALTER TABLE [ingredients] DROP COLUMN [count_sheet_category];
