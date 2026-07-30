CREATE TABLE [order_lines] (
    [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]              BIGINT         NOT NULL,
    [daily_meal_option_id]  BIGINT         NOT NULL,
    [unit_price]            DECIMAL(10, 2) NOT NULL,
    [quantity]              INT            NOT NULL,
    [line_total]            DECIMAL(10, 2) NOT NULL,
    CONSTRAINT [FK_order_lines_orders] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_order_lines_daily_meal_options] FOREIGN KEY ([daily_meal_option_id]) REFERENCES [daily_meal_options]([id]),
    CONSTRAINT [CK_order_lines_quantity_positive] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_order_lines_order_id] ON [order_lines]([order_id]);
