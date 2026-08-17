-- Stage 5 seed data: the ingredient master list plus an initial GRV (and matching ledger
-- movement) for each, so ingredient-requirement warnings have realistic current-stock figures
-- from day one.
--
-- Deliberately does NOT also seed the stage spec's example component recipes/meals here.
-- recipes is a new FK child of component_catalog (V50); every pre-existing integration test
-- that does componentCatalogRepository.deleteAll() in its @AfterEach (there are a dozen) expects
-- that call to succeed unconditionally, and it stops doing so the moment any component_catalog
-- row has a recipe pointing at it — seeding one permanently here would break the *entire*
-- existing regression suite the moment it exists, not just this migration. Recipes/example
-- meals are still fully exercised via the API and this stage's own integration tests, which
-- create their own components and tear them down (recipe rows included) within their own test
-- lifecycle, so no lingering recipe ever reaches another test class. See PHASE2_PROGRESS.md.

DECLARE @adminId BIGINT = (SELECT id FROM users WHERE email = 'admin@canteen.local');
DECLARE @ingredientId BIGINT, @grvId BIGINT;

-- name, unit, count_sheet_category, initial quantity, cost_per_unit
DECLARE @seed TABLE (name NVARCHAR(100), unit NVARCHAR(10), category NVARCHAR(20), qty DECIMAL(10,4), cost DECIMAL(10,2));
INSERT INTO @seed (name, unit, category, qty, cost) VALUES
    ('Rice', 'KG', 'DRYSTOCK', 50.00, 18.00),
    ('Chicken', 'KG', 'BULK', 45.00, 55.00),
    ('Cooking Oil', 'LITRE', 'DRYSTOCK', 40.00, 32.00),
    ('Salt', 'KG', 'DRYSTOCK', 50.00, 8.00),
    ('Cabbage', 'KG', 'FVEG', 40.00, 12.00),
    ('Carrots', 'KG', 'FVEG', 40.00, 14.00),
    ('Mayonnaise', 'KG', 'DRYSTOCK', 40.00, 45.00),
    ('Vinegar', 'LITRE', 'DRYSTOCK', 40.00, 20.00),
    ('Sugar', 'KG', 'DRYSTOCK', 50.00, 16.00),
    ('Potatoes', 'KG', 'FVEG', 60.00, 15.00),
    ('Beef (Stewing)', 'KG', 'BULK', 45.00, 90.00),
    ('Samp', 'KG', 'DRYSTOCK', 50.00, 14.00),
    ('Pork', 'KG', 'BULK', 45.00, 75.00),
    ('Sirloin', 'KG', 'BULK', 45.00, 130.00),
    ('Onions', 'KG', 'FVEG', 50.00, 13.00),
    ('Tomatoes', 'KG', 'FVEG', 50.00, 18.00),
    ('Garlic', 'KG', 'DRYSTOCK', 40.00, 60.00),
    ('Beef Stock Powder', 'KG', 'DRYSTOCK', 40.00, 85.00),
    ('Mixed Herbs', 'KG', 'DRYSTOCK', 40.00, 95.00),
    ('Butter', 'KG', 'DRYSTOCK', 40.00, 105.00),
    ('Milk', 'LITRE', 'DRYSTOCK', 50.00, 22.00);

DECLARE @name NVARCHAR(100), @unit NVARCHAR(10), @category NVARCHAR(20), @qty DECIMAL(10,4), @cost DECIMAL(10,2);
DECLARE seed_cursor CURSOR LOCAL FAST_FORWARD FOR SELECT name, unit, category, qty, cost FROM @seed;
OPEN seed_cursor;
FETCH NEXT FROM seed_cursor INTO @name, @unit, @category, @qty, @cost;
WHILE @@FETCH_STATUS = 0
BEGIN
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES (@name, @unit, @category);
    SET @ingredientId = SCOPE_IDENTITY();

    INSERT INTO [grv] ([ingredient_id], [quantity], [cost_per_unit], [supplier_name], [received_by])
        VALUES (@ingredientId, @qty, @cost, 'Initial Stock Load', @adminId);
    SET @grvId = SCOPE_IDENTITY();

    INSERT INTO [ingredient_stock_movements]
        ([ingredient_id], [movement_type], [quantity], [cost_per_unit], [source_type], [source_id], [recorded_by])
        VALUES (@ingredientId, 'RECEIVED', @qty, @cost, 'GRV', @grvId, @adminId);

    FETCH NEXT FROM seed_cursor INTO @name, @unit, @category, @qty, @cost;
END
CLOSE seed_cursor;
DEALLOCATE seed_cursor;
