-- Stage 5 Part G: deliberately lightweight, purely informational purchase ordering — no
-- approval workflow, and no FK to grv (receiving stock and having placed an order are
-- independent actions in this stage).
CREATE TABLE [purchase_orders] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [supplier_name]   NVARCHAR(100) NOT NULL,
    [status]          NVARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    [created_by]      BIGINT        NOT NULL,
    [created_at]      DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_purchase_orders_created_by] FOREIGN KEY ([created_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_purchase_orders_status] CHECK ([status] IN ('DRAFT', 'SUBMITTED', 'RECEIVED'))
);

CREATE TABLE [purchase_order_lines] (
    [id]                 BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [purchase_order_id]  BIGINT         NOT NULL,
    [ingredient_id]      BIGINT         NOT NULL,
    [quantity]           DECIMAL(10, 4) NOT NULL,
    [note]               NVARCHAR(255)  NULL,
    CONSTRAINT [FK_purchase_order_lines_purchase_order] FOREIGN KEY ([purchase_order_id]) REFERENCES [purchase_orders]([id]),
    CONSTRAINT [FK_purchase_order_lines_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [CK_purchase_order_lines_quantity] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_purchase_order_lines_purchase_order_id] ON [purchase_order_lines]([purchase_order_id]);
