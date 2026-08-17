-- Stage 5 Part C: prevents double-deduction if the chef adds more items to the day's plan after
-- an earlier batch has already been confirmed — only unreviewed rows are ever picked up by an
-- ingredient-requirements calculation (see IngredientRequirementConfirmation, V56).
ALTER TABLE [daily_meal_options] ADD [ingredients_reviewed] BIT NOT NULL DEFAULT 0;
ALTER TABLE [daily_component_stock] ADD [ingredients_reviewed] BIT NOT NULL DEFAULT 0;
