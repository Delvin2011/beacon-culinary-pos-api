-- Beverages and the bar (Part B).
-- A beverage is a meal in the Beverages group: always available, no meal period. Each meal has a
-- preparation station (KITCHEN, BAR, or NONE for cold drinks handed over at payment); order lines
-- snapshot it, and an order has one status row per station it needs (order_station_status). The
-- order's own status is derived from those rows by the status engine (OrderStationService).
-- Split across GO batches: later statements reference columns added in earlier ones.

INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order]) VALUES
    (NULL, 'BEVERAGES', 'Beverages', 3);
DECLARE @beverages BIGINT = (SELECT [id] FROM [catalog_categories] WHERE [name] = 'Beverages' AND [parent_id] IS NULL);
INSERT INTO [catalog_categories] ([parent_id], [group_code], [name], [sort_order]) VALUES
    (@beverages, 'BEVERAGES', 'Hot beverages',  1),
    (@beverages, 'BEVERAGES', 'Cold beverages', 2);

-- Set meals and light meals are prepared in the kitchen; a beverage is BAR (hot, the default) or
-- NONE (cold). Every existing meal is a set meal, so KITCHEN.
ALTER TABLE [meal_catalog] ADD [station] NVARCHAR(10) NOT NULL
    CONSTRAINT [DF_meal_catalog_station] DEFAULT 'KITCHEN'
    CONSTRAINT [CK_meal_catalog_station] CHECK ([station] IN ('KITCHEN', 'BAR', 'NONE'));

-- Beverages have no meal period. SQL Server won't change a column's nullability while an index or
-- foreign key uses it, so both are dropped and re-created.
DROP INDEX [IX_daily_meal_options_date_period] ON [daily_meal_options];
ALTER TABLE [daily_meal_options] DROP CONSTRAINT [FK_daily_meal_options_meal_periods];
ALTER TABLE [daily_meal_options] ALTER COLUMN [meal_period_id] BIGINT NULL;
ALTER TABLE [daily_meal_options] ADD CONSTRAINT [FK_daily_meal_options_meal_periods]
    FOREIGN KEY ([meal_period_id]) REFERENCES [meal_periods]([id]);
CREATE INDEX [IX_daily_meal_options_date_period] ON [daily_meal_options]([option_date], [meal_period_id]);

-- Snapshot of the meal's station at sale; every existing line was prepared in the kitchen.
ALTER TABLE [order_lines] ADD [station] NVARCHAR(10) NOT NULL
    CONSTRAINT [DF_order_lines_station] DEFAULT 'KITCHEN'
    CONSTRAINT [CK_order_lines_station] CHECK ([station] IN ('KITCHEN', 'BAR', 'NONE'));

CREATE TABLE [order_station_status] (
    [order_id]   BIGINT       NOT NULL,
    [station]    NVARCHAR(10) NOT NULL,
    [status]     NVARCHAR(12) NOT NULL,
    [updated_at] DATETIME2    NOT NULL CONSTRAINT [DF_order_station_status_updated_at] DEFAULT SYSUTCDATETIME(),
    [updated_by] BIGINT       NULL,
    CONSTRAINT [PK_order_station_status] PRIMARY KEY ([order_id], [station]),
    CONSTRAINT [FK_order_station_status_order] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_order_station_status_updated_by] FOREIGN KEY ([updated_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_order_station_status_station] CHECK ([station] IN ('KITCHEN', 'BAR')),
    CONSTRAINT [CK_order_station_status_status] CHECK ([status] IN ('PENDING', 'IN_PROGRESS', 'DONE'))
);
CREATE INDEX [IX_order_station_status_station] ON [order_station_status]([station], [status]);

-- NULL for an overall order status change; the station for a station's own change.
ALTER TABLE [order_status_events] ADD [station] NVARCHAR(10) NULL
    CONSTRAINT [CK_order_status_events_station] CHECK ([station] IN ('KITCHEN', 'BAR'));

-- The Barista: uses the bar display (BAR_DISPLAY until U2). PIN only.
ALTER TABLE [users] DROP CONSTRAINT [CK_users_role];
ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [CK_users_role]
    CHECK ([role] IN ('USER', 'CASHIER', 'ADMIN', 'KITCHEN', 'STOCK_CLERK', 'STOCK_ADMIN', 'HEAD_CHEF', 'BARISTA'));
GO

UPDATE [meal_catalog] SET [station] = 'KITCHEN';

-- Existing orders keep working: every order still in the kitchen flow gets its KITCHEN row, with
-- the order's status (COLLECTED becomes DONE: the kitchen's part was finished). Voided and
-- refunded orders get none.
INSERT INTO [order_station_status] ([order_id], [station], [status])
SELECT [id], 'KITCHEN', CASE WHEN [status] = 'COLLECTED' THEN 'DONE' ELSE [status] END
FROM [orders]
WHERE [status] IN ('PENDING', 'IN_PROGRESS', 'DONE', 'COLLECTED');
