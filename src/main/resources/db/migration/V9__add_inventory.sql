-- Add stock_quantity column to product_variants
ALTER TABLE [product_variants] ADD [stock_quantity] INT NOT NULL DEFAULT 0;

-- Seed initial stock using EXEC to force a new batch after the ALTER TABLE
-- Beer & Ciders (Singles: 50 units, Packs: 20 units)
EXEC('UPDATE [product_variants] SET [stock_quantity] = 50  WHERE [variant_name] = ''Single''    AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 1)');
EXEC('UPDATE [product_variants] SET [stock_quantity] = 20  WHERE [variant_name] = ''Pack of 6'' AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 1)');

-- Spirits & Mixers (Shots: 100 units, Bottles: 15 units)
EXEC('UPDATE [product_variants] SET [stock_quantity] = 100 WHERE [variant_name] = ''Shot''   AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 2)');
EXEC('UPDATE [product_variants] SET [stock_quantity] = 15  WHERE [variant_name] = ''Bottle'' AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 2)');

-- Wines (Glasses: 60 units, Bottles: 20 units)
EXEC('UPDATE [product_variants] SET [stock_quantity] = 60  WHERE [variant_name] = ''Glass''  AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 3)');
EXEC('UPDATE [product_variants] SET [stock_quantity] = 20  WHERE [variant_name] = ''Bottle'' AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 3)');

-- Soft Drinks (Singles: 100 units, Packs: 30 units)
EXEC('UPDATE [product_variants] SET [stock_quantity] = 100 WHERE [variant_name] = ''Single''    AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 4)');
EXEC('UPDATE [product_variants] SET [stock_quantity] = 30  WHERE [variant_name] = ''Pack of 6'' AND [product_id] IN (SELECT [id] FROM [products] WHERE [category_id] = 4)');
