-- Catalogue categories and light meals (Part A).
-- A category tree replaces any "light meal" flag. Every meal sits in a leaf: "Set meals" (a
-- top-level leaf) or a subcategory under a group ("Light meals"; "Beverages" in V97). A meal's
-- group is its category's group_code; SET_MEALS are planned daily with portions, the others are
-- always available with NULL (unlimited) portions and an "Out today" switch (out_on = that day).
-- Split across GO batches: later statements reference columns added in earlier ones.

CREATE TABLE [catalog_categories] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [parent_id]  BIGINT        NULL,
    [group_code] NVARCHAR(12)  NOT NULL,
    [name]       NVARCHAR(60)  NOT NULL,
    [sort_order] INT           NOT NULL CONSTRAINT [DF_catalog_categories_sort_order] DEFAULT 0,
    [active]     BIT           NOT NULL CONSTRAINT [DF_catalog_categories_active] DEFAULT 1,
    CONSTRAINT [FK_catalog_categories_parent] FOREIGN KEY ([parent_id]) REFERENCES [catalog_categories]([id]),
    CONSTRAINT [CK_catalog_categories_group_code] CHECK ([group_code] IN ('SET_MEALS', 'LIGHT_MEALS', 'BEVERAGES')),
    CONSTRAINT [UQ_catalog_categories_parent_name] UNIQUE ([parent_id], [name])
);

INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order]) VALUES
    (NULL, 'SET_MEALS',   'Set meals',   1),
    (NULL, 'LIGHT_MEALS', 'Light meals', 2);

DECLARE @lightMeals BIGINT = (SELECT [id] FROM [catalog_categories] WHERE [name] = 'Light meals' AND [parent_id] IS NULL);
INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order]) VALUES
    (@lightMeals, 'LIGHT_MEALS', 'Burgers',       1),
    (@lightMeals, 'LIGHT_MEALS', 'Wraps',         2),
    (@lightMeals, 'LIGHT_MEALS', 'Sandwiches',    3),
    (@lightMeals, 'LIGHT_MEALS', 'Chips & sides', 4);
GO

ALTER TABLE [meal_catalog] ADD
    [category_id] BIGINT NULL CONSTRAINT [FK_meal_catalog_category] FOREIGN KEY REFERENCES [catalog_categories]([id]),
    [out_on]      DATE   NULL;
GO

-- Every existing meal becomes a set meal; the manager moves burgers, wraps etc. afterwards.
UPDATE [meal_catalog]
SET [category_id] = (SELECT [id] FROM [catalog_categories] WHERE [name] = 'Set meals' AND [parent_id] IS NULL);
ALTER TABLE [meal_catalog] ALTER COLUMN [category_id] BIGINT NOT NULL;
CREATE INDEX [IX_meal_catalog_category_id] ON [meal_catalog]([category_id]);

-- Breakfast and Lunch accept set meals; All Day accepts light meals only. Existing All Day
-- options are left as they are; only new options are validated.
ALTER TABLE [meal_periods] ADD [accepts] NVARCHAR(12) NOT NULL
    CONSTRAINT [DF_meal_periods_accepts] DEFAULT 'SET_MEALS'
    CONSTRAINT [CK_meal_periods_accepts] CHECK ([accepts] IN ('SET_MEALS', 'LIGHT_MEALS'));
GO

UPDATE [meal_periods] SET [accepts] = 'LIGHT_MEALS' WHERE [all_day] = 1;

-- Unlimited portions = NULL. The >= 0 checks still apply to non-NULL values; SQL Server won't
-- change a column's nullability while a CHECK references it, so they're dropped and re-added.
ALTER TABLE [daily_meal_options] DROP CONSTRAINT [CK_daily_meal_options_planned_portions_non_negative];
ALTER TABLE [daily_meal_options] DROP CONSTRAINT [CK_daily_meal_options_portions_remaining_non_negative];
ALTER TABLE [daily_meal_options] ALTER COLUMN [planned_portions] INT NULL;
ALTER TABLE [daily_meal_options] ALTER COLUMN [portions_remaining] INT NULL;
ALTER TABLE [daily_meal_options] ADD
    CONSTRAINT [CK_daily_meal_options_planned_portions_non_negative] CHECK ([planned_portions] >= 0),
    CONSTRAINT [CK_daily_meal_options_portions_remaining_non_negative] CHECK ([portions_remaining] >= 0);

-- At most one always-on option (NULL portions) per meal per day, so creating them is idempotent
-- even when two till requests race.
CREATE UNIQUE INDEX [UQ_daily_meal_options_always_on] ON [daily_meal_options]([meal_catalog_id], [option_date])
    WHERE [planned_portions] IS NULL;
