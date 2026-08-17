-- Stage 5 Part C: one row per confirmed "POST .../confirm-ingredient-requirements" batch (one
-- date + meal period). Every CONSUMED_FOR_PREP ingredient_stock_movement produced by that
-- confirmation points back at this row for traceability, per the spec's "referencing this
-- confirmation batch" instruction — not in the spec's literal Flyway listing, added for the same
-- reason as waste_entries/stock_takes (V54/V55); see PHASE2_PROGRESS.md.
CREATE TABLE [ingredient_requirement_confirmations] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [planning_date]   DATE      NOT NULL,
    [meal_period_id]  BIGINT    NOT NULL,
    [confirmed_by]    BIGINT    NOT NULL,
    [confirmed_at]    DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_ingredient_requirement_confirmations_meal_period] FOREIGN KEY ([meal_period_id]) REFERENCES [meal_periods]([id]),
    CONSTRAINT [FK_ingredient_requirement_confirmations_confirmed_by] FOREIGN KEY ([confirmed_by]) REFERENCES [users]([id])
);
