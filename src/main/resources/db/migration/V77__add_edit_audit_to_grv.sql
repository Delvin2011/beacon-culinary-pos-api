-- Stage 5.2.6: same-day GRV correction — an admin can fix a data-entry mistake (invoice number,
-- supplier, note, per-line quantity/cost) on the same calendar day it was received, never after.
-- edited_by/edited_at record who last corrected it and when; GRVs otherwise stay an immutable
-- receiving record.
ALTER TABLE [grv] ADD [edited_by] BIGINT NULL, [edited_at] DATETIME2 NULL;
GO

ALTER TABLE [grv] ADD CONSTRAINT [FK_grv_edited_by] FOREIGN KEY ([edited_by]) REFERENCES [users]([id]);
