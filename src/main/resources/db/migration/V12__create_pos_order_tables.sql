-- orders table
CREATE TABLE [orders] (
    [id]            BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_type]    VARCHAR(20)    NOT NULL,
    [table_number]  INT            NULL,
    [status]        VARCHAR(20)    NOT NULL,
    [subtotal]      DECIMAL(10, 2) NOT NULL,
    [tax_total]     DECIMAL(10, 2) NOT NULL,
    [total]         DECIMAL(10, 2) NOT NULL,
    [created_at]    DATETIME       NOT NULL DEFAULT GETDATE(),
    [cashier_id]    BIGINT         NOT NULL,
    CONSTRAINT [FK_orders_cashier] FOREIGN KEY ([cashier_id]) REFERENCES [users]([id])
    );

-- order_items table
CREATE TABLE [order_items] (
    [id]                     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]               BIGINT         NOT NULL,
    [menu_item_variant_id]   BIGINT         NOT NULL,
    [quantity]               INT            NOT NULL,
    [unit_price]             DECIMAL(10, 2) NOT NULL,
    [line_total]             DECIMAL(10, 2) NOT NULL,
    CONSTRAINT [FK_order_items_orders] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_order_items_variants] FOREIGN KEY ([menu_item_variant_id]) REFERENCES [menu_item_variants]([id]),
    CONSTRAINT [CK_order_items_quantity_positive] CHECK ([quantity] > 0)
    );

-- order_item_modifiers table
CREATE TABLE [order_item_modifiers] (
    [id]                     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_item_id]          BIGINT         NOT NULL,
    [modifier_id]            BIGINT         NOT NULL,
    [price_delta_snapshot]   DECIMAL(10, 2) NOT NULL,
    CONSTRAINT [FK_order_item_modifiers_order_items] FOREIGN KEY ([order_item_id]) REFERENCES [order_items]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_order_item_modifiers_modifiers] FOREIGN KEY ([modifier_id]) REFERENCES [modifiers]([id])
    );

-- Indexes
CREATE INDEX [IX_orders_cashier_id] ON [orders]([cashier_id]);
CREATE INDEX [IX_order_items_order_id] ON [order_items]([order_id]);
CREATE INDEX [IX_order_items_menu_item_variant_id] ON [order_items]([menu_item_variant_id]);
CREATE INDEX [IX_order_item_modifiers_order_item_id] ON [order_item_modifiers]([order_item_id]);
