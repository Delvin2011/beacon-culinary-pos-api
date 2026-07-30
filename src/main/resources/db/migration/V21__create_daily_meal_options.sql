CREATE TABLE [daily_meal_options] (
    [id]                  BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [meal_period_id]      BIGINT         NOT NULL,
    [meal_catalog_id]     BIGINT         NOT NULL,
    [option_date]         DATE           NOT NULL,
    [name]                NVARCHAR(100)  NOT NULL,
    [description]         NVARCHAR(255)  NULL,
    [price]               DECIMAL(10, 2) NOT NULL,
    [planned_portions]    INT            NOT NULL,
    [portions_remaining]  INT            NOT NULL,
    CONSTRAINT [FK_daily_meal_options_meal_periods] FOREIGN KEY ([meal_period_id]) REFERENCES [meal_periods]([id]),
    CONSTRAINT [FK_daily_meal_options_meal_catalog] FOREIGN KEY ([meal_catalog_id]) REFERENCES [meal_catalog]([id]),
    CONSTRAINT [CK_daily_meal_options_planned_portions_non_negative] CHECK ([planned_portions] >= 0),
    CONSTRAINT [CK_daily_meal_options_portions_remaining_non_negative] CHECK ([portions_remaining] >= 0)
);

CREATE INDEX [IX_daily_meal_options_date_period] ON [daily_meal_options]([option_date], [meal_period_id]);
