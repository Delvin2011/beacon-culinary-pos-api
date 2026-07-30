-- Cross-cutting seed data per metadatafiles/phase1-backend-detailed.md (added once,
-- after Stage 1.3's migrations exist). Catalog rows are permanent reference data.
-- The daily_meal_options/daily_component_stock rows are seeded for "today" as of
-- whenever this migration is applied (CAST(SYSUTCDATETIME() AS DATE)) — by design,
-- since redoing daily planning every day via /admin/daily-* is the whole point of the
-- two-layer model. This migration exists purely so a freshly-seeded dev database has
-- something to browse/order on day one without manual admin API calls.

DECLARE @riceId BIGINT, @chickenId BIGINT, @potatoesId BIGINT, @beefId BIGINT;
DECLARE @riceChickenId BIGINT, @potatoesBeefId BIGINT, @riceBeefId BIGINT, @chickenPotatoesId BIGINT;
DECLARE @breakfastId BIGINT = (SELECT id FROM meal_periods WHERE name = 'Breakfast');
DECLARE @lunchId BIGINT = (SELECT id FROM meal_periods WHERE name = 'Lunch');
DECLARE @today DATE = CAST(SYSUTCDATETIME() AS DATE);

-- 1. Component catalog (4 entries, predefined extra prices)
INSERT INTO component_catalog (name, extra_price) VALUES ('Rice', 8.00);
SET @riceId = SCOPE_IDENTITY();
INSERT INTO component_catalog (name, extra_price) VALUES ('Chicken', 12.00);
SET @chickenId = SCOPE_IDENTITY();
INSERT INTO component_catalog (name, extra_price) VALUES ('Potatoes', 8.00);
SET @potatoesId = SCOPE_IDENTITY();
INSERT INTO component_catalog (name, extra_price) VALUES ('Beef', 15.00);
SET @beefId = SCOPE_IDENTITY();

-- 2. Meal catalog (4 entries, predefined prices — every pairing of the 4 components above)
INSERT INTO meal_catalog (name, description, price) VALUES ('Rice & Chicken', 'Steamed rice with grilled chicken.', 45.00);
SET @riceChickenId = SCOPE_IDENTITY();
INSERT INTO meal_catalog (name, description, price) VALUES ('Potatoes & Beef', 'Roast potatoes with braised beef.', 50.00);
SET @potatoesBeefId = SCOPE_IDENTITY();
INSERT INTO meal_catalog (name, description, price) VALUES ('Rice & Beef', 'Steamed rice with braised beef.', 48.00);
SET @riceBeefId = SCOPE_IDENTITY();
INSERT INTO meal_catalog (name, description, price) VALUES ('Chicken & Potatoes', 'Grilled chicken with roast potatoes.', 47.00);
SET @chickenPotatoesId = SCOPE_IDENTITY();

-- 3. Composition links (informational, for menu display only — not a pricing/stock relationship)
INSERT INTO meal_catalog_components (meal_catalog_id, component_catalog_id) VALUES
    (@riceChickenId, @riceId), (@riceChickenId, @chickenId),
    (@potatoesBeefId, @potatoesId), (@potatoesBeefId, @beefId),
    (@riceBeefId, @riceId), (@riceBeefId, @beefId),
    (@chickenPotatoesId, @chickenId), (@chickenPotatoesId, @potatoesId);

-- 4. Daily options for today — 2 breakfast, 2 lunch. "Potatoes & Beef" at lunch is
-- deliberately sold out (planned_portions = 0) so the no-replenishment path is
-- immediately testable without waiting for stock to deplete.
INSERT INTO daily_meal_options (meal_period_id, meal_catalog_id, option_date, name, description, price, planned_portions, portions_remaining) VALUES
    (@breakfastId, @riceBeefId,        @today, 'Rice & Beef',        'Steamed rice with braised beef.',         48.00, 15, 15),
    (@breakfastId, @chickenPotatoesId, @today, 'Chicken & Potatoes', 'Grilled chicken with roast potatoes.',     47.00, 15, 15),
    (@lunchId,     @riceChickenId,     @today, 'Rice & Chicken',     'Steamed rice with grilled chicken.',       45.00, 20, 20),
    (@lunchId,     @potatoesBeefId,    @today, 'Potatoes & Beef',    'Roast potatoes with braised beef.',        50.00, 0,  0);

-- 5. Daily component stock for all four components at lunch. Chicken is deliberately
-- sold out (buffer_remaining = 0); Rice and Chicken both belong to a *different* dish
-- ("Rice & Chicken") than the sold-out "Potatoes & Beef" option above, so the
-- cross-dish extras path is testable immediately.
INSERT INTO daily_component_stock (component_catalog_id, meal_period_id, option_date, extra_price, buffer_quantity, buffer_remaining) VALUES
    (@riceId,     @lunchId, @today, 8.00,  15, 15),
    (@chickenId,  @lunchId, @today, 12.00, 10, 0),
    (@potatoesId, @lunchId, @today, 8.00,  15, 15),
    (@beefId,     @lunchId, @today, 15.00, 10, 10);
