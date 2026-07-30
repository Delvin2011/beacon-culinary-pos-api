-- Retiring the à-la-carte menu model (categories/items/variants/modifier groups) and its
-- POS orders in favor of the set-meal / daily-planning model (see Stage 1.2 of
-- metadatafiles/phase1-backend-detailed.md). Drop order respects FK dependencies.

ALTER TABLE [order_item_modifiers] DROP CONSTRAINT [FK_order_item_modifiers_order_items];
ALTER TABLE [order_item_modifiers] DROP CONSTRAINT [FK_order_item_modifiers_modifiers];
DROP TABLE [order_item_modifiers];

ALTER TABLE [order_items] DROP CONSTRAINT [FK_order_items_orders];
ALTER TABLE [order_items] DROP CONSTRAINT [FK_order_items_variants];
DROP TABLE [order_items];

ALTER TABLE [orders] DROP CONSTRAINT [FK_orders_cashier];
DROP TABLE [orders];

ALTER TABLE [modifiers] DROP CONSTRAINT [FK_modifiers_groups];
DROP TABLE [modifiers];

ALTER TABLE [modifier_groups] DROP CONSTRAINT [FK_modifier_groups_items];
DROP TABLE [modifier_groups];

ALTER TABLE [menu_item_variants] DROP CONSTRAINT [FK_menu_item_variants_items];
DROP TABLE [menu_item_variants];

ALTER TABLE [menu_items] DROP CONSTRAINT [FK_menu_items_categories];
DROP TABLE [menu_items];

DROP TABLE [menu_categories];
