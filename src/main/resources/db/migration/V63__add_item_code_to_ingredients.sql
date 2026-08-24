-- Stage 5.2.2: optional supplier/internal item code, shown for reference in Ingredient Master
-- and GRV's item search. Free text, no uniqueness constraint — codes may vary by supplier.
ALTER TABLE [ingredients] ADD [item_code] NVARCHAR(50) NULL;
