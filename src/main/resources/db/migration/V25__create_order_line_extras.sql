CREATE TABLE [order_line_extras] (
    [id]                        BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_line_id]             BIGINT         NOT NULL,
    [daily_component_stock_id]  BIGINT         NOT NULL,
    [price_delta]               DECIMAL(10, 2) NOT NULL,
    [quantity]                  INT            NOT NULL,
    [line_total]                DECIMAL(10, 2) NOT NULL,
    CONSTRAINT [FK_order_line_extras_order_lines] FOREIGN KEY ([order_line_id]) REFERENCES [order_lines]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_order_line_extras_daily_component_stock] FOREIGN KEY ([daily_component_stock_id]) REFERENCES [daily_component_stock]([id]),
    CONSTRAINT [CK_order_line_extras_quantity_positive] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_order_line_extras_order_line_id] ON [order_line_extras]([order_line_id]);
