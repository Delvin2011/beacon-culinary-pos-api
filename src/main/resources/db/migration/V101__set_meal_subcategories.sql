-- Set meals takes subcategories, like Light meals and Beverages: Breakfast and Lunch to start.
-- "Set meals" itself stays assignable, so existing set meals stay where they are; staff move them
-- into a subcategory from Catalogue Management. The subcategories only group meals for now: a
-- Breakfast meal can still be planned under Lunch (no link to meal periods yet).

DECLARE @setMeals BIGINT = (SELECT [id] FROM [catalog_categories] WHERE [group_code] = 'SET_MEALS' AND [parent_id] IS NULL);
INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order]) VALUES
    (@setMeals, 'SET_MEALS', 'Breakfast', 1),
    (@setMeals, 'SET_MEALS', 'Lunch',     2);
