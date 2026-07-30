-- Adds an "All Day" meal period with no time window, for consumables (drinks, sweets,
-- etc.) that should be orderable at any hour, not just during Breakfast/Lunch service.
-- Existing periods keep NOT NULL-equivalent bounds via the all_day=0 default; only the
-- all-day period itself has NULL start_time/end_time, read by MealPeriod.isActiveAt()
-- as "always active" rather than as a wide-but-bounded window (avoids day-boundary
-- edge cases a 00:00–23:59:59 placeholder window would have).

ALTER TABLE [meal_periods] ALTER COLUMN [start_time] TIME NULL;
ALTER TABLE [meal_periods] ALTER COLUMN [end_time] TIME NULL;
ALTER TABLE [meal_periods] ADD [all_day] BIT NOT NULL DEFAULT 0;
GO

INSERT INTO [meal_periods] ([name], [start_time], [end_time], [all_day]) VALUES
    ('All Day', NULL, NULL, 1);
