CREATE TABLE [daily_component_stock] (
    [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [component_catalog_id]  BIGINT         NOT NULL,
    [meal_period_id]        BIGINT         NOT NULL,
    [option_date]           DATE           NOT NULL,
    [extra_price]           DECIMAL(10, 2) NOT NULL,
    [buffer_quantity]       INT            NOT NULL,
    [buffer_remaining]      INT            NOT NULL,
    CONSTRAINT [FK_daily_component_stock_component_catalog] FOREIGN KEY ([component_catalog_id]) REFERENCES [component_catalog]([id]),
    CONSTRAINT [FK_daily_component_stock_meal_periods] FOREIGN KEY ([meal_period_id]) REFERENCES [meal_periods]([id]),
    CONSTRAINT [CK_daily_component_stock_buffer_quantity_non_negative] CHECK ([buffer_quantity] >= 0),
    CONSTRAINT [CK_daily_component_stock_buffer_remaining_non_negative] CHECK ([buffer_remaining] >= 0)
);

CREATE INDEX [IX_daily_component_stock_date_period] ON [daily_component_stock]([option_date], [meal_period_id]);
