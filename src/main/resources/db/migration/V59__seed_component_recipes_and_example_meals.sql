-- Stage 5 seed data (part 2): the eight component recipes from the stage spec (batch size = 10
-- portions each) and four example meals demonstrating component reuse across meals. Split from
-- V58 so the two concerns stay independently re-runnable in dev: V58 seeds ingredients+GRVs,
-- this migration attaches recipes to components and composes example meals from them.
--
-- Every component_catalog/ingredients row this migration needs — Rice/Chicken from V27, the 21
-- ingredients from V58, plus this migration's own new components — is looked up and (re)created
-- only if missing, never unconditionally inserted. Both tables get blanket-wiped in
-- @AfterEach by a dozen-plus pre-existing integration tests (and this stage's own), and none of
-- those earlier migrations ever re-run to restore what they wiped — so by the time this
-- migration actually executes there is no guarantee any of that data survived. Each lookup uses
-- `TOP 1 ... ORDER BY id` rather than a bare subquery so a hypothetical duplicate name never
-- crashes the whole migration with "subquery returned more than 1 value".

IF NOT EXISTS (SELECT 1 FROM [component_catalog] WHERE [name] = 'Rice')
    INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Rice', 8.00);
DECLARE @riceComponentId BIGINT = (SELECT TOP 1 id FROM [component_catalog] WHERE [name] = 'Rice' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [component_catalog] WHERE [name] = 'Chicken')
    INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Chicken', 12.00);
DECLARE @chickenComponentId BIGINT = (SELECT TOP 1 id FROM [component_catalog] WHERE [name] = 'Chicken' ORDER BY id);

DECLARE @coleslawComponentId BIGINT, @sampComponentId BIGINT, @beefStewComponentId BIGINT,
        @potatoesMashComponentId BIGINT, @porkComponentId BIGINT, @sirloinComponentId BIGINT;

INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Coleslaw', 10.00);
SET @coleslawComponentId = SCOPE_IDENTITY();
INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Samp', 10.00);
SET @sampComponentId = SCOPE_IDENTITY();
INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Beef Stew', 18.00);
SET @beefStewComponentId = SCOPE_IDENTITY();
INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Potatoes Mash', 10.00);
SET @potatoesMashComponentId = SCOPE_IDENTITY();
INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Pork', 20.00);
SET @porkComponentId = SCOPE_IDENTITY();
INSERT INTO [component_catalog] ([name], [extra_price]) VALUES ('Sirloin', 28.00);
SET @sirloinComponentId = SCOPE_IDENTITY();

-- Ingredient lookups. V58 seeds all 21 of these, but not necessarily "immediately prior in the
-- same Flyway run" in practice — V58 may already have been applied in an earlier dev/test
-- session, and this stage's own integration tests blanket-wipe `ingredients` in their
-- @AfterEach exactly like the component_catalog tests above do. So each one is (re)created here
-- if missing, using the same unit/category V58 used — an initial GRV isn't recreated for a
-- straggler like this since that's a display nicety for Part D, not something this migration's
-- recipes/meals depend on.
IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Rice')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Rice', 'KG', 'DRYSTOCK');
DECLARE @riceId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Rice' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Chicken')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Chicken', 'KG', 'BULK');
DECLARE @chickenId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Chicken' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Cooking Oil')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Cooking Oil', 'LITRE', 'DRYSTOCK');
DECLARE @cookingOilId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Cooking Oil' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Salt')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Salt', 'KG', 'DRYSTOCK');
DECLARE @saltId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Salt' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Cabbage')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Cabbage', 'KG', 'FVEG');
DECLARE @cabbageId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Cabbage' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Carrots')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Carrots', 'KG', 'FVEG');
DECLARE @carrotsId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Carrots' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Mayonnaise')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Mayonnaise', 'KG', 'DRYSTOCK');
DECLARE @mayonnaiseId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Mayonnaise' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Vinegar')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Vinegar', 'LITRE', 'DRYSTOCK');
DECLARE @vinegarId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Vinegar' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Sugar')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Sugar', 'KG', 'DRYSTOCK');
DECLARE @sugarId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Sugar' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Potatoes')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Potatoes', 'KG', 'FVEG');
DECLARE @potatoesId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Potatoes' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Beef (Stewing)')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Beef (Stewing)', 'KG', 'BULK');
DECLARE @beefId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Beef (Stewing)' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Samp')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Samp', 'KG', 'DRYSTOCK');
DECLARE @sampId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Samp' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Pork')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Pork', 'KG', 'BULK');
DECLARE @porkId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Pork' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Sirloin')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Sirloin', 'KG', 'BULK');
DECLARE @sirloinId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Sirloin' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Onions')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Onions', 'KG', 'FVEG');
DECLARE @onionsId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Onions' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Tomatoes')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Tomatoes', 'KG', 'FVEG');
DECLARE @tomatoesId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Tomatoes' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Garlic')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Garlic', 'KG', 'DRYSTOCK');
DECLARE @garlicId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Garlic' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Beef Stock Powder')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Beef Stock Powder', 'KG', 'DRYSTOCK');
DECLARE @beefStockPowderId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Beef Stock Powder' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Mixed Herbs')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Mixed Herbs', 'KG', 'DRYSTOCK');
DECLARE @mixedHerbsId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Mixed Herbs' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Butter')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Butter', 'KG', 'DRYSTOCK');
DECLARE @butterId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Butter' ORDER BY id);

IF NOT EXISTS (SELECT 1 FROM [ingredients] WHERE [name] = 'Milk')
    INSERT INTO [ingredients] ([name], [unit], [count_sheet_category]) VALUES ('Milk', 'LITRE', 'DRYSTOCK');
DECLARE @milkId BIGINT = (SELECT TOP 1 id FROM [ingredients] WHERE [name] = 'Milk' ORDER BY id);

-- Recipes (batch size = 10 portions each) + lines.

DECLARE @recipeId BIGINT;

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@riceComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @riceId, 1.5000), (@recipeId, @cookingOilId, 0.2500), (@recipeId, @saltId, 0.0050);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@chickenComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @chickenId, 2.5000), (@recipeId, @cookingOilId, 0.1000), (@recipeId, @saltId, 0.0100);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@coleslawComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @cabbageId, 1.0000), (@recipeId, @carrotsId, 0.3000), (@recipeId, @mayonnaiseId, 0.4000),
    (@recipeId, @vinegarId, 0.0500), (@recipeId, @sugarId, 0.0200);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@sampComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @sampId, 2.0000), (@recipeId, @saltId, 0.0200);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@beefStewComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @beefId, 2.5000), (@recipeId, @onionsId, 0.5000), (@recipeId, @tomatoesId, 0.3000),
    (@recipeId, @garlicId, 0.0500), (@recipeId, @beefStockPowderId, 0.0500), (@recipeId, @cookingOilId, 0.2000);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@potatoesMashComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @potatoesId, 3.0000), (@recipeId, @butterId, 0.3000), (@recipeId, @milkId, 0.2000), (@recipeId, @saltId, 0.0100);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@porkComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @porkId, 2.5000), (@recipeId, @cookingOilId, 0.1000), (@recipeId, @mixedHerbsId, 0.0200), (@recipeId, @saltId, 0.0100);

INSERT INTO [recipes] ([component_catalog_id], [batch_size]) VALUES (@sirloinComponentId, 10);
SET @recipeId = SCOPE_IDENTITY();
INSERT INTO [recipe_lines] ([recipe_id], [ingredient_id], [quantity]) VALUES
    (@recipeId, @sirloinId, 3.0000), (@recipeId, @cookingOilId, 0.1500), (@recipeId, @saltId, 0.0100), (@recipeId, @mixedHerbsId, 0.0200);

-- Example meals, composed via meal_catalog_components — demonstrates component reuse
-- (Potatoes Mash appears in both "Pork & Mash" and "Sirloin Steak & Mash").

DECLARE @riceChickenColeslawId BIGINT, @sampBeefStewId BIGINT, @porkMashId BIGINT, @sirloinMashId BIGINT;

INSERT INTO [meal_catalog] ([name], [description], [price]) VALUES ('Rice, Chicken & Coleslaw', 'Steamed rice with grilled chicken and coleslaw.', 55.00);
SET @riceChickenColeslawId = SCOPE_IDENTITY();
INSERT INTO [meal_catalog] ([name], [description], [price]) VALUES ('Samp & Beef Stew', 'Samp served with slow-braised beef stew.', 60.00);
SET @sampBeefStewId = SCOPE_IDENTITY();
INSERT INTO [meal_catalog] ([name], [description], [price]) VALUES ('Pork & Mash', 'Herb-seasoned pork with creamy mashed potatoes.', 58.00);
SET @porkMashId = SCOPE_IDENTITY();
INSERT INTO [meal_catalog] ([name], [description], [price]) VALUES ('Sirloin Steak & Mash', 'Grilled sirloin steak with creamy mashed potatoes.', 75.00);
SET @sirloinMashId = SCOPE_IDENTITY();

INSERT INTO [meal_catalog_components] ([meal_catalog_id], [component_catalog_id]) VALUES
    (@riceChickenColeslawId, @riceComponentId), (@riceChickenColeslawId, @chickenComponentId), (@riceChickenColeslawId, @coleslawComponentId),
    (@sampBeefStewId, @sampComponentId), (@sampBeefStewId, @beefStewComponentId),
    (@porkMashId, @porkComponentId), (@porkMashId, @potatoesMashComponentId),
    (@sirloinMashId, @sirloinComponentId), (@sirloinMashId, @potatoesMashComponentId);
