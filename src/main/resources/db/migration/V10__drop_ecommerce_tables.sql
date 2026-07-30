-- Dropping the online-store domain (products/carts/checkout) — this backend is being
-- repurposed into a canteen POS. Drop order respects FK dependencies.

ALTER TABLE [wishlist] DROP CONSTRAINT [FK_wishlist_products];
ALTER TABLE [wishlist] DROP CONSTRAINT [FK_wishlist_users];
DROP TABLE [wishlist];

ALTER TABLE [cart_items] DROP CONSTRAINT [FK_cart_items_carts];
ALTER TABLE [cart_items] DROP CONSTRAINT [FK_cart_items_variants];
DROP TABLE [cart_items];

DROP TABLE [carts];

ALTER TABLE [order_items] DROP CONSTRAINT [order_items_orders_id_fk];
ALTER TABLE [order_items] DROP CONSTRAINT [order_items_products_id_fk];
ALTER TABLE [order_items] DROP CONSTRAINT [FK_order_items_variants];
DROP TABLE [order_items];

ALTER TABLE [orders] DROP CONSTRAINT [orders_users_id_fk];
DROP TABLE [orders];

ALTER TABLE [product_variants] DROP CONSTRAINT [FK_product_variants_products];
DROP TABLE [product_variants];

ALTER TABLE [products] DROP CONSTRAINT [FK_products_categories];
DROP TABLE [products];

DROP TABLE [categories];
