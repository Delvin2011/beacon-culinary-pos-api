-- Stage 5 Part A: raw-ingredient master data. Physically-stocked items only — NONE-category
-- items (salaries, utilities) are explicitly out of scope. Current stock is never a column on
-- this table; it is always derived from ingredient_stock_movements (V52).
CREATE TABLE [ingredients] (
    [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]                  NVARCHAR(100) NOT NULL,
    [unit]                  NVARCHAR(10)  NOT NULL,
    [count_sheet_category]  NVARCHAR(20)  NOT NULL,
    [active]                BIT NOT NULL DEFAULT 1,
    CONSTRAINT [CK_ingredients_unit] CHECK ([unit] IN ('KG', 'LITRE', 'EACH')),
    CONSTRAINT [CK_ingredients_count_sheet_category] CHECK ([count_sheet_category] IN ('PREP', 'BULK', 'DRYSTOCK', 'FVEG'))
);
