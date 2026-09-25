-- Daily planning review status + actual quantities. A daily meal option / component stock row now
-- moves PLANNED -> INGREDIENTS_REVIEWED (confirm-ingredient-requirements) -> READY (an actual
-- quantity recorded), and only READY rows are sold by the POS. portions_remaining /
-- buffer_remaining now count down from the actual, so they stay 0 until an actual is recorded.
--
-- status replaces the ingredients_reviewed flag (V51): ingredients_reviewed = 1 is exactly
-- status <> 'PLANNED', and keeping both would leave two sources of truth.
--
-- Split across GO batches: the backfills reference columns added just above, and SQL Server
-- compiles a script as one batch (same issue V35/V41/V61/V64/V73/V79 hit).
ALTER TABLE [daily_meal_options] ADD
    [status]          NVARCHAR(25) NOT NULL CONSTRAINT [DF_daily_meal_options_status] DEFAULT 'PLANNED',
    [actual_portions] INT NULL;
ALTER TABLE [daily_component_stock] ADD
    [status]          NVARCHAR(25) NOT NULL CONSTRAINT [DF_daily_component_stock_status] DEFAULT 'PLANNED',
    [actual_quantity] INT NULL;
GO

-- Backfill. Rows from before today, or that have already sold something, were on sale under the
-- old rules — they become READY with actual = planned, so remaining (= actual - sold) and every
-- historical report stay exactly as they were. Anything else (today/future, nothing sold yet)
-- takes the status its review flag implies and waits for an actual, like a newly planned row.
UPDATE [daily_meal_options]
SET [status] = 'READY', [actual_portions] = [planned_portions]
WHERE [option_date] < CAST(GETDATE() AS DATE) OR [portions_remaining] < [planned_portions];

UPDATE [daily_meal_options]
SET [status] = CASE WHEN [ingredients_reviewed] = 1 THEN 'INGREDIENTS_REVIEWED' ELSE 'PLANNED' END,
    [portions_remaining] = 0
WHERE [status] = 'PLANNED' AND [actual_portions] IS NULL;

UPDATE [daily_component_stock]
SET [status] = 'READY', [actual_quantity] = [buffer_quantity]
WHERE [option_date] < CAST(GETDATE() AS DATE) OR [buffer_remaining] < [buffer_quantity];

UPDATE [daily_component_stock]
SET [status] = CASE WHEN [ingredients_reviewed] = 1 THEN 'INGREDIENTS_REVIEWED' ELSE 'PLANNED' END,
    [buffer_remaining] = 0
WHERE [status] = 'PLANNED' AND [actual_quantity] IS NULL;
GO

ALTER TABLE [daily_meal_options] ADD CONSTRAINT [CK_daily_meal_options_status]
    CHECK ([status] IN ('PLANNED', 'INGREDIENTS_REVIEWED', 'READY'));
ALTER TABLE [daily_component_stock] ADD CONSTRAINT [CK_daily_component_stock_status]
    CHECK ([status] IN ('PLANNED', 'INGREDIENTS_REVIEWED', 'READY'));
GO

-- ingredients_reviewed's DEFAULT constraint was created unnamed in V51, so look its generated
-- name up before the column can be dropped.
DECLARE @sql NVARCHAR(MAX) = N'';
SELECT @sql = @sql + N'ALTER TABLE ' + QUOTENAME(t.[name]) + N' DROP CONSTRAINT ' + QUOTENAME(dc.[name]) + N';'
FROM sys.default_constraints dc
INNER JOIN sys.columns c ON c.[object_id] = dc.[parent_object_id] AND c.[column_id] = dc.[parent_column_id]
INNER JOIN sys.tables t ON t.[object_id] = dc.[parent_object_id]
WHERE c.[name] = 'ingredients_reviewed' AND t.[name] IN ('daily_meal_options', 'daily_component_stock');
EXEC sp_executesql @sql;
GO

ALTER TABLE [daily_meal_options] DROP COLUMN [ingredients_reviewed];
ALTER TABLE [daily_component_stock] DROP COLUMN [ingredients_reviewed];
