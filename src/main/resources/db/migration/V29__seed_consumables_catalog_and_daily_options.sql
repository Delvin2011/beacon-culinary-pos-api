-- Example consumables (drinks, sweets) sold under the "All Day" period added in V28,
-- so it's immediately browsable/orderable via GET /menu/today?period=ALL_DAY without
-- manual admin calls. These are standalone items in their own right (not a component of
-- another dish), so they're modeled as meal_catalog entries planned for today, same as
-- any Breakfast/Lunch dish — just tied to a period with no time window.
--
-- Same "today" caveat as V27: option_date is fixed to whenever this migration is
-- applied, and re-planning for subsequent days is expected to happen via
-- /admin/daily-options in real operation.

DECLARE @allDayId BIGINT = (SELECT id FROM meal_periods WHERE name = 'All Day');
DECLARE @coolDrinkId BIGINT, @sweetTreatId BIGINT, @bottledWaterId BIGINT;
DECLARE @today DATE = CAST(SYSUTCDATETIME() AS DATE);

INSERT INTO meal_catalog (name, description, price) VALUES ('Cool Drink', 'Chilled 330ml canned soft drink.', 15.00);
SET @coolDrinkId = SCOPE_IDENTITY();
INSERT INTO meal_catalog (name, description, price) VALUES ('Sweet Treat', 'Assorted baked sweet of the day.', 10.00);
SET @sweetTreatId = SCOPE_IDENTITY();
INSERT INTO meal_catalog (name, description, price) VALUES ('Bottled Water', 'Sealed 500ml bottled water.', 10.00);
SET @bottledWaterId = SCOPE_IDENTITY();

INSERT INTO daily_meal_options (meal_period_id, meal_catalog_id, option_date, name, description, price, planned_portions, portions_remaining) VALUES
    (@allDayId, @coolDrinkId,    @today, 'Cool Drink',    'Chilled 330ml canned soft drink.', 15.00, 40, 40),
    (@allDayId, @sweetTreatId,   @today, 'Sweet Treat',   'Assorted baked sweet of the day.',  10.00, 30, 30),
    (@allDayId, @bottledWaterId, @today, 'Bottled Water', 'Sealed 500ml bottled water.',       10.00, 40, 40);
