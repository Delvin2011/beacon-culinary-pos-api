CREATE TABLE [order_status_events] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]    BIGINT       NOT NULL,
    [from_status] NVARCHAR(20) NULL,
    [to_status]   NVARCHAR(20) NOT NULL,
    [changed_by]  BIGINT       NOT NULL,
    [changed_at]  DATETIME2    NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_order_status_events_orders] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_order_status_events_changed_by] FOREIGN KEY ([changed_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_order_status_events_from_status] CHECK ([from_status] IN ('PENDING', 'IN_PROGRESS', 'DONE', 'COLLECTED', 'VOIDED', 'REFUNDED')),
    CONSTRAINT [CK_order_status_events_to_status] CHECK ([to_status] IN ('PENDING', 'IN_PROGRESS', 'DONE', 'COLLECTED', 'VOIDED', 'REFUNDED'))
);

CREATE INDEX [IX_order_status_events_order] ON [order_status_events]([order_id]);
