-- Stage 5.2.4: a Waste sheet targets exactly one location (header-level, not per-line — a
-- deliberate simplification vs. per-line, kept consistent with Issue's shape). Required at the
-- application level for WASTE-type requests only; ISSUE stays fixed Main Store -> Kitchen (Stage
-- 5.2.1), ORDER isn't location-specific — both leave this column NULL.
ALTER TABLE [stock_requests] ADD [location_id] BIGINT NULL;
GO

ALTER TABLE [stock_requests]
    ADD CONSTRAINT [FK_stock_requests_location] FOREIGN KEY ([location_id]) REFERENCES [locations]([id]);
