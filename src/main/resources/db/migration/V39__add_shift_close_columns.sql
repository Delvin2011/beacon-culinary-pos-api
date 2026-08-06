-- Stage 2.5: cash-drawer reconciliation fields, all persisted (snapshotted) at close time
-- rather than left to be recomputed later — same audit-integrity reasoning as
-- orders.original_total (Stage 2.6).
ALTER TABLE [shifts] ADD [closing_cash] DECIMAL(10, 2) NULL;
ALTER TABLE [shifts] ADD [expected_cash] DECIMAL(10, 2) NULL;
ALTER TABLE [shifts] ADD [variance] DECIMAL(10, 2) NULL;
ALTER TABLE [shifts] ADD [variance_reason_code] NVARCHAR(30) NULL
    CONSTRAINT [CK_shifts_variance_reason_code] CHECK ([variance_reason_code] IN
        ('CASH_COUNTING_ERROR', 'THEFT_SUSPECTED', 'UNRECORDED_TRANSACTION', 'OTHER'));
ALTER TABLE [shifts] ADD [variance_note] NVARCHAR(255) NULL;
ALTER TABLE [shifts] ADD [variance_authorized_by] BIGINT NULL
    CONSTRAINT [FK_shifts_variance_authorized_by] FOREIGN KEY REFERENCES [users]([id]);