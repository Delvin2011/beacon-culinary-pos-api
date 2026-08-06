-- Stage 2.6: marks which extras were reversed by an EXTRAS_ONLY adjustment. Rows are flagged,
-- not deleted, so the original order detail remains reconstructable.
ALTER TABLE [order_line_extras] ADD [adjusted] BIT NOT NULL DEFAULT 0;
