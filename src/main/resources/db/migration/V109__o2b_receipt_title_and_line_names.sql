-- O2b additions (docs/2026-11-17-o2b-receipt-data-api-contract.md, section 8.2).
--
-- 1. receipt_settings.receipt_title: the slip's heading (e.g. "Tax Invoice"); NULL prints none.
-- 2. order_lines.name and order_line_extras.component_name: the names at sale, so a later rename of
--    a menu item or component doesn't change old receipts. Sized as their sources
--    (daily_meal_options.name, component_catalog.name). Existing rows stay NULL and show the current
--    name, as before; no backfill.

ALTER TABLE [receipt_settings] ADD [receipt_title] NVARCHAR(50) NULL;

ALTER TABLE [order_lines] ADD [name] NVARCHAR(100) NULL;

ALTER TABLE [order_line_extras] ADD [component_name] NVARCHAR(50) NULL;
