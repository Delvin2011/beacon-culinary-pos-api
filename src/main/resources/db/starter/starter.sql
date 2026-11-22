-- T0-7 starter template for a NEW customer database (docs/2026-11-22-new-customer-database.md).
--
-- Optional, and applied only when the operator chooses it (NewDatabaseCommand --starter), after the baseline and
-- every migration. Not a Flyway migration: it isn't in db/migration or db/baseline, so it never runs on its own
-- and isn't recorded in flyway_schema_history.
--
-- Sensible defaults a customer may rename, deactivate or delete: two timed meal periods, the usual catalogue
-- subcategories, component categories and count-sheet categories. No menu, no prices, no stock, no users.
--
-- Safe to run twice: each row is added only when one with the same name (and parent) isn't there yet. Kept in
-- step with the latest schema by NewDatabaseIntegrationTests, which applies it to a fresh baseline database.

SET XACT_ABORT ON;
BEGIN TRANSACTION;

-- Meal periods: timed periods that take set meals (All Day, for light meals, is in the baseline).
INSERT INTO [meal_periods] ([name], [start_time], [end_time], [all_day], [accepts], [active], [sort_order])
SELECT v.[name], v.[start_time], v.[end_time], 0, 'SET_MEALS', 1, v.[sort_order]
FROM (VALUES (N'Breakfast', CAST('07:00' AS time), CAST('10:00' AS time), 10),
             (N'Lunch', CAST('12:00' AS time), CAST('14:30' AS time), 20)) AS v([name], [start_time], [end_time], [sort_order])
WHERE NOT EXISTS (SELECT 1 FROM [meal_periods] p WHERE p.[name] = v.[name]);

-- Catalogue subcategories, under the fixed top-level groups.
INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order], [active])
SELECT g.[id], g.[group_code], v.[name], v.[sort_order], 1
FROM (VALUES ('SET_MEALS', N'Breakfast', 1),
             ('SET_MEALS', N'Lunch', 2),
             ('LIGHT_MEALS', N'Burgers', 1),
             ('LIGHT_MEALS', N'Wraps', 2),
             ('LIGHT_MEALS', N'Sandwiches', 3),
             ('LIGHT_MEALS', N'Chips & sides', 4),
             ('BEVERAGES', N'Hot beverages', 1),
             ('BEVERAGES', N'Cold beverages', 2)) AS v([group_code], [name], [sort_order])
JOIN [catalog_categories] g ON g.[parent_id] IS NULL AND g.[group_code] = v.[group_code]
WHERE NOT EXISTS (SELECT 1 FROM [catalog_categories] c WHERE c.[parent_id] = g.[id] AND c.[name] = v.[name]);

-- Component categories (what a set meal's components are grouped by on the planning screens).
INSERT INTO [component_categories] ([name], [sort_order], [active])
SELECT v.[name], v.[sort_order], 1
FROM (VALUES (N'Meat', 1), (N'Chicken', 2), (N'Fish', 3), (N'Starch', 4), (N'Vegetables & relishes', 5),
             (N'Sauces & gravies', 6), (N'Desserts & fruit', 7)) AS v([name], [sort_order])
WHERE NOT EXISTS (SELECT 1 FROM [component_categories] c WHERE c.[name] = v.[name]);

-- Count-sheet categories (every ingredient needs one; stock takes are counted by them).
INSERT INTO [count_sheet_categories] ([name], [active])
SELECT v.[name], 1
FROM (VALUES (N'POULTRY'), (N'MEAT'), (N'SEAFOOD'), (N'FVEG'), (N'FROZEN'), (N'FRUIT'), (N'DRYSTOCK'), (N'DAIRY'),
             (N'BAKERY'), (N'BEVERAGES'), (N'CLEANING'), (N'PACKAGING'), (N'JUICE'), (N'CORDIAL'), (N'PPE'),
             (N'PLANT MILK'), (N'SOFT DRINKS'), (N'TIZERS'), (N'JUICES'), (N'ICED TEA'), (N'WATER'),
             (N'FLAVOURED WATER'), (N'ENERGY DRINKS'), (N'MILKSHAKES'), (N'HOT BEVERAGES'), (N'DRY GOODS'),
             (N'CATERING JUICES'), (N'CATERING WATER')) AS v([name])
WHERE NOT EXISTS (SELECT 1 FROM [count_sheet_categories] c WHERE c.[name] = v.[name]);

COMMIT TRANSACTION;
