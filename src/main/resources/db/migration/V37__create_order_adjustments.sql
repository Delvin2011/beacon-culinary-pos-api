-- Stage 2.6: audit trail of whole-order/extras-only void and refund adjustments.
CREATE TABLE [order_adjustments] (
    [id]            BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]      BIGINT         NOT NULL,
    [scope]         NVARCHAR(20)   NOT NULL,
    [action]        NVARCHAR(10)   NOT NULL,
    [reason_code]   NVARCHAR(30)   NOT NULL,
    [note]          NVARCHAR(255)  NULL,
    [amount]        DECIMAL(10, 2) NOT NULL,
    [requested_by]  BIGINT         NOT NULL,
    [authorized_by] BIGINT         NOT NULL,
    [created_at]    DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_order_adjustments_orders] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_order_adjustments_requested_by] FOREIGN KEY ([requested_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_order_adjustments_authorized_by] FOREIGN KEY ([authorized_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_order_adjustments_scope] CHECK ([scope] IN ('WHOLE_ORDER', 'EXTRAS_ONLY')),
    CONSTRAINT [CK_order_adjustments_action] CHECK ([action] IN ('VOID', 'REFUND')),
    CONSTRAINT [CK_order_adjustments_reason_code] CHECK ([reason_code] IN
        ('WRONG_ORDER', 'CUSTOMER_COMPLAINT', 'KITCHEN_ERROR', 'DUPLICATE_ENTRY', 'OUT_OF_STOCK_ERROR', 'OTHER'))
);

CREATE INDEX [IX_order_adjustments_order_id] ON [order_adjustments]([order_id]);
