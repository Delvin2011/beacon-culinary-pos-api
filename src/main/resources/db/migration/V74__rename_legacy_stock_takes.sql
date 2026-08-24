-- Stage 5.2.5: Stock Take is rebuilt as a two-step submit/review flow with its own header/lines
-- shape (location-selectable, STOCK_CLERK can submit) — a genuinely different entity from the
-- original Stage 5 Part F one-step direct write, not an in-place restructure of it (unlike GRV in
-- Stage 5.2.2). The old table is renamed out of the way so the "stock_takes" name is free for the
-- new shape below; its historical rows and the STOCK_TAKE_ADJUSTMENT movements they produced stay
-- completely untouched — no source_id re-pointing needed, since nothing here migrates rows *into*
-- the new shape, unlike GRV's restructure.
EXEC sp_rename 'stock_takes', 'legacy_stock_takes';
