-- Replaces the fixed CountSheetCategory enum (PREP/BULK/DRYSTOCK/FVEG) with a real reference
-- table, same pattern as locations (V60) — new categories can now be added without a code
-- change or a CHECK-constraint migration. Order below is preserved for count sheet display/print
-- grouping (id ascending = the order categories were seeded in).
CREATE TABLE [count_sheet_categories] (
    [id]     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]   NVARCHAR(50) NOT NULL,
    [active] BIT NOT NULL DEFAULT 1
);

INSERT INTO [count_sheet_categories] ([name]) VALUES
    ('POULTRY'),
    ('MEAT'),
    ('SEAFOOD'),
    ('FVEG'),
    ('FROZEN'),
    ('FRUIT'),
    ('DRYSTOCK'),
    ('DAIRY'),
    ('BAKERY'),
    ('BEVERAGES'),
    ('CLEANING'),
    ('PACKAGING'),
    ('JUICE'),
    ('CORDIAL'),
    ('PPE'),
    ('PLANT MILK'),
    ('SOFT DRINKS'),
    ('TIZERS'),
    ('JUICES'),
    ('ICED TEA'),
    ('WATER'),
    ('FLAVOURED WATER'),
    ('ENERGY DRINKS'),
    ('MILKSHAKES'),
    ('HOT BEVERAGES'),
    ('DRY GOODS'),
    ('CATERING JUICES'),
    ('CATERING WATER');
