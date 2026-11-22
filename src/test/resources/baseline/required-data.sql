-- REQUIRED reference data: only what a customer needs to trade (docs/2026-11-22-new-customer-database.md).
-- Copied into the baseline by BaselineScriptGenerator. Everything else (meal periods other than All Day,
-- subcategories, component and count-sheet categories) is in the optional starter, db/starter.

-- Stock locations (T0-5c, V115): the primary receives every delivery and supplies every issue; Kitchen is the
-- production location and every station's source. Both rows in one statement: the trigger refuses any statement
-- that leaves the table without exactly one active primary. Customers rename them at onboarding.
INSERT INTO [locations] ([name], [active], [kind], [sort_order]) VALUES
    (N'Main Store', 1, 'PRIMARY', 10),
    (N'Kitchen', 1, 'SECONDARY', 20);

INSERT INTO [location_assignments] ([role], [location_id])
SELECT [role], (SELECT [id] FROM [locations] WHERE [name] = N'Kitchen')
FROM (VALUES ('PRODUCTION'), ('STATION_BAR'), ('STATION_KITCHEN'), ('STATION_NONE')) AS r([role]);

-- The All Day meal period (V28, T0-5a): light meals are offered under "the period that accepts LIGHT_MEALS",
-- which the admin API can't create (it creates timed set-meal periods) and which can't be deactivated.
INSERT INTO [meal_periods] ([name], [start_time], [end_time], [all_day], [accepts], [active], [sort_order])
VALUES (N'All Day', NULL, NULL, 1, 'LIGHT_MEALS', 1, 30);

-- The fixed top-level catalogue groups (V96, V97): no API adds, renames or deactivates them, and every
-- catalogue subcategory and meal sits under one.
INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order], [active]) VALUES
    (NULL, 'SET_MEALS', N'Set meals', 1, 1),
    (NULL, 'LIGHT_MEALS', N'Light meals', 2, 1),
    (NULL, 'BEVERAGES', N'Beverages', 3, 1);

-- The single receipt settings row (V108, id 1), blank until onboarding fills it in.
INSERT INTO [receipt_settings] ([id], [show_vat_line]) VALUES (1, 0);
