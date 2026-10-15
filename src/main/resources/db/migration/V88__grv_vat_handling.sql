-- GRV VAT handling (v2). Supplier invoices are captured exactly as written (VAT-inclusive or
-- VAT-exclusive); both VAT bases are stored on every GRV line, and stock cost stays
-- VAT-inclusive. grv_lines.cost_per_unit keeps its meaning as the ledger cost
-- (= unit_cost_incl_vat). Split across GO batches: the backfill references columns added above.

-- Each ingredient's usual VAT treatment; a GRV line falls back to it when no code is given.
ALTER TABLE [ingredients] ADD [default_vat_code] NVARCHAR(12) NOT NULL
    CONSTRAINT [DF_ingredients_default_vat_code] DEFAULT 'STANDARD'
    CONSTRAINT [CK_ingredients_default_vat_code] CHECK ([default_vat_code] IN ('STANDARD', 'ZERO_RATED', 'NO_VAT'));

-- Required for new GRVs (enforced in the application); legacy rows are backfilled below.
ALTER TABLE [grv] ADD [prices_include_vat] BIT NULL;
ALTER TABLE [grv] ADD [invoice_total_incl_vat] DECIMAL(12, 2) NULL;

ALTER TABLE [grv_lines] ADD
    [unit_price_as_invoiced] DECIMAL(12, 4) NULL,
    [vat_code]               NVARCHAR(12)   NULL
        CONSTRAINT [CK_grv_lines_vat_code] CHECK ([vat_code] IN ('STANDARD', 'ZERO_RATED', 'NO_VAT')),
    [vat_rate]               DECIMAL(5, 4)  NULL,
    [unit_cost_excl_vat]     DECIMAL(12, 4) NULL,
    [unit_cost_incl_vat]     DECIMAL(12, 4) NULL,
    [line_excl_vat]          DECIMAL(12, 2) NULL,
    [line_vat]               DECIMAL(12, 2) NULL,
    [line_incl_vat]          DECIMAL(12, 2) NULL;

-- Unit costs to 4 dp: inclusive invoices produce costs such as 15.6522, and 2 dp would let
-- stock valuations drift by cents. Money totals stay 2 dp.
ALTER TABLE [grv_lines] ALTER COLUMN [cost_per_unit] DECIMAL(12, 4) NOT NULL;
ALTER TABLE [ingredient_stock_movements] ALTER COLUMN [cost_per_unit] DECIMAL(12, 4) NULL;
ALTER TABLE [stock_take_lines] ALTER COLUMN [unit_cost] DECIMAL(12, 4) NOT NULL;
GO

-- Legacy GRVs are treated as VAT-inclusive, which is how the ledger already values them, so no
-- ledger value changes. No VAT split is invented: vat_code, vat_rate, unit_cost_excl_vat,
-- line_excl_vat and line_vat stay NULL, because the VAT code of those purchases was never captured.
UPDATE [grv] SET [prices_include_vat] = 1 WHERE [prices_include_vat] IS NULL;

UPDATE [grv_lines]
   SET [unit_price_as_invoiced] = [cost_per_unit],
       [unit_cost_incl_vat]     = [cost_per_unit],
       [line_incl_vat]          = ROUND([quantity_received] * [cost_per_unit], 2)
 WHERE [unit_price_as_invoiced] IS NULL;
