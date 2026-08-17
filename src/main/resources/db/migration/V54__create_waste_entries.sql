-- Stage 5 Part E: raw-ingredient loss (spoilage, breakage, etc). Not in the spec's literal
-- Flyway listing (which only sketches the ingredient_stock_movements ledger), but the endpoint's
-- reason/note fields have no home on the ledger row itself, so this dedicated source table
-- follows the same shape as Grv (V53) — see PHASE2_PROGRESS.md for the reasoning. Creates a
-- WASTED ingredient_stock_movement.
CREATE TABLE [waste_entries] (
    [id]             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [ingredient_id]  BIGINT         NOT NULL,
    [quantity]       DECIMAL(10, 4) NOT NULL,
    [reason]         NVARCHAR(100)  NOT NULL,
    [note]           NVARCHAR(255)  NULL,
    [recorded_by]    BIGINT         NOT NULL,
    [created_at]     DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_waste_entries_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [FK_waste_entries_recorded_by] FOREIGN KEY ([recorded_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_waste_entries_quantity] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_waste_entries_ingredient_id] ON [waste_entries]([ingredient_id]);
