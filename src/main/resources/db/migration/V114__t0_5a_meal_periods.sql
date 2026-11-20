-- T0-5a: editable meal periods (docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md).
--
-- 1. meal_periods.active and sort_order. Every existing period stays active; sort_order follows
--    today's order (by start time, All Day last): Breakfast 10, Lunch 20, All Day 30.
-- 2. Constraints the API relies on:
--    - an All Day period has no times; a timed period has both, start before end (no overnight);
--    - one All Day period at most;
--    - a name is unique among active periods (the column's collation ignores case).
--    Overlap between periods can't be a constraint; the application checks it under a lock.
-- 3. meal_period_changes: insert-only history of every CREATE, UPDATE, DEACTIVATE and ACTIVATE,
--    with the values before and after. Offline sync reads it for the times in force at soldAt.
--
-- Existing data: an All Day row with leftover times has them cleared (they are never read:
-- MealPeriod.isActiveAt treats All Day as always open). Any other row that breaks a rule stops
-- the migration with a message naming it, rather than being changed silently.
-- Audit user columns have no foreign key to users, like V105 and V113: users are hard-deleted.
-- Split across GO batches: CREATE TRIGGER must be the only statement in its batch.

-- 1. Columns --------------------------------------------------------------------------------------

ALTER TABLE [meal_periods] ADD
    [active]     BIT NOT NULL CONSTRAINT [DF_meal_periods_active] DEFAULT 1,
    [sort_order] INT NOT NULL CONSTRAINT [DF_meal_periods_sort_order] DEFAULT 0;
GO

UPDATE mp
SET [sort_order] = ordered.[rn] * 10
FROM [meal_periods] mp
JOIN (
    SELECT [id], ROW_NUMBER() OVER (
        ORDER BY CASE WHEN [all_day] = 1 THEN 1 ELSE 0 END, [start_time], [id]) AS [rn]
    FROM [meal_periods]
) ordered ON ordered.[id] = mp.[id];
GO

-- 2. Existing data, then constraints ---------------------------------------------------------------

UPDATE [meal_periods] SET [start_time] = NULL, [end_time] = NULL
WHERE [all_day] = 1 AND ([start_time] IS NOT NULL OR [end_time] IS NOT NULL);
GO

DECLARE @problem NVARCHAR(400) = (
    SELECT TOP 1 CONCAT('Meal period ', [id], ' (', [name], ') has no times or ends before it starts.')
    FROM [meal_periods]
    WHERE [all_day] = 0 AND ([start_time] IS NULL OR [end_time] IS NULL OR [start_time] >= [end_time])
    ORDER BY [id]);
IF @problem IS NULL
    SET @problem = (
        SELECT TOP 1 CONCAT('More than one meal period is called ', [name], '; rename one before V114.')
        FROM [meal_periods] GROUP BY [name] HAVING COUNT(*) > 1 ORDER BY [name]);
IF @problem IS NULL AND (SELECT COUNT(*) FROM [meal_periods] WHERE [all_day] = 1) > 1
    SET @problem = N'More than one All Day meal period; keep one before V114.';
IF @problem IS NOT NULL
    THROW 51000, @problem, 1;
GO

ALTER TABLE [meal_periods] ADD CONSTRAINT [CK_meal_periods_times] CHECK (
    ([all_day] = 1 AND [start_time] IS NULL AND [end_time] IS NULL)
    OR ([all_day] = 0 AND [start_time] IS NOT NULL AND [end_time] IS NOT NULL AND [start_time] < [end_time]));
GO

CREATE UNIQUE INDEX [UX_meal_periods_active_name] ON [meal_periods] ([name]) WHERE [active] = 1;
GO

CREATE UNIQUE INDEX [UX_meal_periods_one_all_day] ON [meal_periods] ([all_day]) WHERE [all_day] = 1;
GO

-- 3. History (insert-only) ------------------------------------------------------------------------

CREATE TABLE [meal_period_changes] (
    [id]              BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT [PK_meal_period_changes] PRIMARY KEY,
    [meal_period_id]  BIGINT        NOT NULL
                          CONSTRAINT [FK_meal_period_changes_meal_periods] REFERENCES [meal_periods] ([id]),
    [action]          NVARCHAR(12)  NOT NULL
                          CONSTRAINT [CK_meal_period_changes_action]
                          CHECK ([action] IN ('CREATE', 'UPDATE', 'DEACTIVATE', 'ACTIVATE')),
    [old_name]        NVARCHAR(50)  NULL,
    [old_start_time]  TIME          NULL,
    [old_end_time]    TIME          NULL,
    [old_sort_order]  INT           NULL,
    [old_active]      BIT           NULL,
    [new_name]        NVARCHAR(50)  NOT NULL,
    [new_start_time]  TIME          NULL,
    [new_end_time]    TIME          NULL,
    [new_sort_order]  INT           NOT NULL,
    [new_active]      BIT           NOT NULL,
    [changed_at]      DATETIME2     NOT NULL,
    [changed_by]      BIGINT        NOT NULL,
    [changed_by_name] NVARCHAR(255) NOT NULL,
    -- CREATE has no "before"; every other action has one.
    CONSTRAINT [CK_meal_period_changes_before] CHECK (
        ([action] = 'CREATE' AND [old_name] IS NULL AND [old_sort_order] IS NULL AND [old_active] IS NULL)
        OR ([action] <> 'CREATE' AND [old_name] IS NOT NULL AND [old_sort_order] IS NOT NULL AND [old_active] IS NOT NULL))
);
GO

-- Newest first per period (history?periodId=, last change per period, times in force at a time).
CREATE INDEX [IX_meal_period_changes_period_changed_at]
    ON [meal_period_changes] ([meal_period_id], [changed_at] DESC, [id] DESC);
GO

CREATE TRIGGER [trg_meal_period_changes_insert_only] ON [meal_period_changes]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Meal period changes are insert-only.', 1;
END;
GO
