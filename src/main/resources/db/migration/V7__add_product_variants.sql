-- 1. Create product_variants table
CREATE TABLE [product_variants] (
    [id]           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [product_id]   BIGINT               NOT NULL,
    [variant_name] NVARCHAR(255)        NOT NULL,
    [price]        DECIMAL(10, 2)       NOT NULL,
    CONSTRAINT [FK_product_variants_products] FOREIGN KEY ([product_id]) REFERENCES [products]([id]) ON DELETE CASCADE
);

-- 2. Delete cart_items data before altering columns
DELETE FROM [cart_items];

-- 3. Alter cart_items: drop old unique constraint, drop FK to products, drop product_id column
ALTER TABLE [cart_items] DROP CONSTRAINT [UQ_cart_items_cart_product];
ALTER TABLE [cart_items] DROP CONSTRAINT [FK_cart_items_products];
ALTER TABLE [cart_items] DROP COLUMN [product_id];

-- 4. Add variant_id to cart_items (table is empty, so NOT NULL is fine without a default)
ALTER TABLE [cart_items] ADD [variant_id] BIGINT NOT NULL;
ALTER TABLE [cart_items] ADD CONSTRAINT [FK_cart_items_variants]      FOREIGN KEY ([variant_id]) REFERENCES [product_variants]([id]) ON DELETE CASCADE;
ALTER TABLE [cart_items] ADD CONSTRAINT [UQ_cart_items_cart_variant]  UNIQUE ([cart_id], [variant_id]);

-- 5. Add nullable variant_id to order_items
ALTER TABLE [order_items] ADD [variant_id] BIGINT NULL;
ALTER TABLE [order_items] ADD CONSTRAINT [FK_order_items_variants] FOREIGN KEY ([variant_id]) REFERENCES [product_variants]([id]);

-- 6. Clear dependent data before reseeding products
DELETE FROM [order_items];

-- product_variants references products; table is empty so safe to delete products
DELETE FROM [products];
DELETE FROM [categories];

-- 7. Drop price column from products (no rows remain)
ALTER TABLE [products] DROP COLUMN [price];

-- 8. Reseed identity counters
DBCC CHECKIDENT ('categories',       RESEED, 0);
DBCC CHECKIDENT ('products',         RESEED, 0);
DBCC CHECKIDENT ('product_variants', RESEED, 0);

-- 9. Insert categories
INSERT INTO [categories] ([name]) VALUES
    ('Beer & Ciders'),
    ('Spirits & Mixers'),
    ('Wines'),
    ('Soft Drinks');

-- 10. Insert products (no price column)
INSERT INTO [products] ([name], [description], [category_id]) VALUES
-- Beer & Ciders (category 1) — IDs 1-7
('Castle Lager',        'Castle Lager beer.',             1),
('Savanna Dry',         'Savanna Dry cider.',             1),
('Windhoek Draught',    'Windhoek Draught beer.',         1),
('Carling Black Label', 'Carling Black Label lager.',     1),
('Hunters Dry',         'Hunters Dry cider.',             1),
('Amstel Lager',        'Amstel premium lager.',          1),
('Heineken',            'Heineken international lager.',  1),
-- Spirits & Mixers (category 2) — IDs 8-14
('Smirnoff Vodka',      'Premium vodka.',                 2),
('Jameson Whiskey',     'Irish whiskey.',                 2),
('Jack Daniels',        'Tennessee whiskey.',             2),
('Captain Morgan',      'Spiced rum.',                    2),
('Gin',                 'London dry gin.',                2),
('Brandy',              'South African brandy.',          2),
('Jagermeister',        'Herbal liqueur.',                2),
-- Wines (category 3) — IDs 15-17
('House Red Wine',      'House red wine.',                3),
('House White Wine',    'House white wine.',              3),
('Rose Wine',           'Rose wine.',                     3),
-- Soft Drinks (category 4) — IDs 18-25
('Coca-Cola',           '330ml Coke.',                    4),
('Coke Zero',           'Sugar-free Coke.',               4),
('Sprite',              'Lemon-lime soda.',               4),
('Fanta Orange',        'Orange soda.',                   4),
('Stoney Ginger Beer',  'Ginger beer soft drink.',        4),
('Red Bull',            'Energy drink.',                  4),
('Still Water',         '500ml bottled water.',           4),
('Sparkling Water',     'Sparkling mineral water.',       4);

-- 11. Insert product variants
INSERT INTO [product_variants] ([product_id], [variant_name], [price]) VALUES
-- Castle Lager (product 1)
(1,  'Single',     32.00),
(1,  'Pack of 6', 182.40),
-- Savanna Dry (product 2)
(2,  'Single',     38.00),
(2,  'Pack of 6', 216.60),
-- Windhoek Draught (product 3)
(3,  'Single',     45.00),
(3,  'Pack of 6', 256.50),
-- Carling Black Label (product 4)
(4,  'Single',     30.00),
(4,  'Pack of 6', 171.00),
-- Hunters Dry (product 5)
(5,  'Single',     36.00),
(5,  'Pack of 6', 205.20),
-- Amstel Lager (product 6)
(6,  'Single',     34.00),
(6,  'Pack of 6', 193.80),
-- Heineken (product 7)
(7,  'Single',     36.00),
(7,  'Pack of 6', 205.20),
-- Smirnoff Vodka (product 8)
(8,  'Shot',       25.00),
(8,  'Bottle',    280.00),
-- Jameson Whiskey (product 9)
(9,  'Shot',       35.00),
(9,  'Bottle',    420.00),
-- Jack Daniels (product 10)
(10, 'Shot',       38.00),
(10, 'Bottle',    450.00),
-- Captain Morgan (product 11)
(11, 'Shot',       45.00),
(11, 'Bottle',    320.00),
-- Gin (product 12)
(12, 'Shot',       40.00),
(12, 'Bottle',    380.00),
-- Brandy (product 13)
(13, 'Shot',       38.00),
(13, 'Bottle',    280.00),
-- Jagermeister (product 14)
(14, 'Shot',       30.00),
(14, 'Bottle',    350.00),
-- House Red Wine (product 15)
(15, 'Glass',      45.00),
(15, 'Bottle',    180.00),
-- House White Wine (product 16)
(16, 'Glass',      45.00),
(16, 'Bottle',    180.00),
-- Rose Wine (product 17)
(17, 'Glass',      48.00),
(17, 'Bottle',    190.00),
-- Coca-Cola (product 18)
(18, 'Single',     20.00),
(18, 'Pack of 6', 114.00),
-- Coke Zero (product 19)
(19, 'Single',     20.00),
(19, 'Pack of 6', 114.00),
-- Sprite (product 20)
(20, 'Single',     20.00),
(20, 'Pack of 6', 114.00),
-- Fanta Orange (product 21)
(21, 'Single',     20.00),
(21, 'Pack of 6', 114.00),
-- Stoney Ginger Beer (product 22)
(22, 'Single',     22.00),
(22, 'Pack of 6', 125.40),
-- Red Bull (product 23)
(23, 'Single',     35.00),
(23, 'Pack of 6', 199.50),
-- Still Water (product 24)
(24, 'Single',     15.00),
(24, 'Pack of 6',  85.50),
-- Sparkling Water (product 25)
(25, 'Single',     18.00),
(25, 'Pack of 6', 102.60);
